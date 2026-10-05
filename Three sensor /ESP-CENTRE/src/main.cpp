// ============================================================================
//  CENTRE RECEIVER  (ESP #1 of 3)
//  - Hosts the WiFi AP + TCP server for the Java desktop app
//  - Has 2 local ultrasonic sensors covering the middle of the play area
//  - Receives 2 distances each from the LEFT and RIGHT corner ESPs (ESP-NOW)
//  - Trilaterates player position from all 6 sensors and sends "x: %.1f y: %.1f"
//
//  Coordinate system (cm):
//    origin (0,0) = directly in front of the LEFT corner ESP
//    +x = towards the right corner ESP, +y = away from the sensor line (into the play area)
//    all 6 sensors are assumed to sit on the line y = 0, facing +y
// ============================================================================
#include <Arduino.h>
#include <WiFi.h>
#include <esp_now.h>
#include "esp_wifi.h"

// ----------------------------- Network --------------------------------------
const char* ssid = "ESP32_G34";
const char* password = "123456789";
const int WIFI_CHANNEL = 6;

// STA MAC addresses of the two corner transmitters (print WiFi.macAddress() on each one)
uint8_t leftMac[]  = {0xE0, 0x5A, 0x1B, 0x1F, 0xD9, 0x20};
uint8_t rightMac[] = {0x00, 0x70, 0x07, 0x7C, 0x8B, 0x04};   // <-- FILL IN right transmitter MAC

WiFiServer Server(80);
WiFiClient client;

// ----------------------------- Geometry -------------------------------------
// X position (cm) of every sensor along the front line. MEASURE THESE.
//   Index order: left ESP s0, s1 | centre ESP s0, s1 | right ESP s0, s1
const float BASELINE_CM = 150.0f;                 // left corner ESP -> right corner ESP
const float CENTRE_X    = BASELINE_CM / 2.0f;     // centre ESP mounting position
const float SENSOR_PITCH_CM = 5.00f;              // spacing between the 2 sensors on one board

const int NUM_UNITS = 3;                          // 0 = left, 1 = centre, 2 = right
const int SENSORS_PER_UNIT = 2;
const int TOTAL_SENSORS = NUM_UNITS * SENSORS_PER_UNIT;

const float SENSOR_X[TOTAL_SENSORS] = {
  0.0f,                           0.0f + 3.00,                  // left unit
  CENTRE_X - SENSOR_PITCH_CM / 2, CENTRE_X + SENSOR_PITCH_CM / 2,          // centre unit
  BASELINE_CM - 3.00,  BASELINE_CM                              // right unit
};
// NOTE: if your corner boards have the two sensors at the exact corner point, set the
// left pair to {0, 0} and the right pair to {BASELINE_CM, BASELINE_CM}.

const int UNIT_OF[TOTAL_SENSORS] = {0, 0, 1, 1, 2, 2};

// ----------------------------- Local sensors (centre) ------------------------
const int TRIG_PINS[SENSORS_PER_UNIT] = {18, 12};
const int ECHO_PINS[SENSORS_PER_UNIT] = {19, 14};

const float MIN_VALID_CM = 3.0f;
const float MAX_VALID_CM = 250.0f;
const unsigned long ECHO_TIMEOUT_US = 15000;
const unsigned long SENSOR_GAP_MS = 5;

const uint8_t FILTER_WINDOW = 5;
const uint8_t FILTER_MIN_VALID = 3;

// ----------------------------- Tracking tuning -------------------------------
const unsigned long REMOTE_TIMEOUT_MS = 300;   // remote data older than this is ignored

// Position fit: all valid sensors are fitted TOGETHER (robust consensus), instead of
// trusting every pair of sensors independently.
const float INLIER_CM = 20.0f;         // a sensor "agrees" with a position if its range is within this
                                       // (a body is ~40 cm wide, so sensors see different parts of it)
const float BODY_RADIUS_CM = 0.0f;     // added to every range so the fit finds the body CENTRE (try 5-10)
const int MIN_INLIERS_ACQUIRE = 3;     // sensors that must agree to START tracking (lower to 2 if it never locks)
const int MIN_INLIERS_TRACK = 2;       // sensors that must agree to keep updating an existing track
const float GRID_CM = 6.0f;            // coarse search resolution (smaller = slower)
const float X_MARGIN_CM = 20.0f;       // search this far outside the left/right corner ESPs
const float PRIOR_WEIGHT = 0.02f;      // tie-break pull towards the current track

// Temporal behaviour
const unsigned long HOLD_MS = 400;     // keep last position this long after losing the player
const float MAX_JUMP_CM = 40.0f;       // a fix further than this from the track is treated as suspect...
const uint8_t RELOCK_CYCLES = 5;       // ...until it repeats this many cycles in a row...
const float RELOCK_CLUSTER_CM = 20.0f; // ...at (roughly) the same new place
const float ALPHA_MIN = 0.15f;         // smoothing: small moves are heavily smoothed,
const float ALPHA_GAIN = 0.01f;        // bigger moves follow faster
const float ALPHA_MAX = 0.7f;

const bool DEBUG_PRINT = true;
const unsigned long DEBUG_INTERVAL_MS = 250;

// ----------------------------- ESP-NOW payload -------------------------------
// Must match the struct in the corner transmitter code exactly.
typedef struct StructMessage {
  float dist[SENSORS_PER_UNIT];
} StructMessage;

portMUX_TYPE remoteMux = portMUX_INITIALIZER_UNLOCKED;
float remoteDist[2][SENSORS_PER_UNIT] = {{-1.0f, -1.0f}, {-1.0f, -1.0f}};   // [0]=left, [1]=right
unsigned long lastRemoteUpdateMs[2] = {0, 0};

void dataRecv(const uint8_t *mac_addr, const uint8_t *incomingData, int len) {
  int slot;
  if (memcmp(mac_addr, leftMac, 6) == 0) slot = 0;
  else if (memcmp(mac_addr, rightMac, 6) == 0) slot = 1;
  else return;
  if (len != (int)sizeof(StructMessage)) return;

  StructMessage m;
  memcpy(&m, incomingData, sizeof(m));

  portENTER_CRITICAL(&remoteMux);
  memcpy(remoteDist[slot], m.dist, sizeof(m.dist));
  lastRemoteUpdateMs[slot] = millis();
  portEXIT_CRITICAL(&remoteMux);
}

// ----------------------------- Median filter ---------------------------------
struct SensorFilter {
  float hist[FILTER_WINDOW];
  uint8_t idx = 0;

  SensorFilter() {
    for (uint8_t i = 0; i < FILTER_WINDOW; i++) hist[i] = NAN;
  }

  float update(float v) {
    hist[idx] = v;
    idx = (idx + 1) % FILTER_WINDOW;

    float tmp[FILTER_WINDOW];
    uint8_t n = 0;
    for (uint8_t i = 0; i < FILTER_WINDOW; i++) {
      if (!isnan(hist[i])) tmp[n++] = hist[i];
    }
    if (n < FILTER_MIN_VALID) return NAN;

    for (uint8_t i = 1; i < n; i++) {
      float key = tmp[i];
      int8_t j = i - 1;
      while (j >= 0 && tmp[j] > key) {
        tmp[j + 1] = tmp[j];
        j--;
      }
      tmp[j + 1] = key;
    }
    return tmp[n / 2];
  }
};

SensorFilter localFilter[SENSORS_PER_UNIT];
float localDist[SENSORS_PER_UNIT];

inline bool validDist(float d) {
  return !isnan(d) && d >= MIN_VALID_CM && d <= MAX_VALID_CM;
}

float measureDistance(int trigPin, int echoPin) {
  digitalWrite(trigPin, LOW);
  delayMicroseconds(2);
  digitalWrite(trigPin, HIGH);
  delayMicroseconds(10);
  digitalWrite(trigPin, LOW);

  unsigned long duration = pulseIn(echoPin, HIGH, ECHO_TIMEOUT_US);
  if (duration == 0) return NAN;   // timeout = nothing seen

  float cm = duration * 0.0343f / 2.0f;
  return validDist(cm) ? cm : NAN;
}

void updateSensors() {
  for (int i = 0; i < SENSORS_PER_UNIT; i++) {
    localDist[i] = localFilter[i].update(measureDistance(TRIG_PINS[i], ECHO_PINS[i]));
    if (i < SENSORS_PER_UNIT - 1) delay(SENSOR_GAP_MS);
  }
}

// ----------------------------- Robust position fit ---------------------------
// Cost of a candidate position: sum over sensors of min(residual^2, INLIER_CM^2).
// A sensor that is looking at something else (limb, wall, another unit's ping) can
// therefore only ever cost a fixed amount - it cannot drag the answer around.
static float costAt(float px, float py, const float *sxs, const float *ds, int m) {
  const float T2 = INLIER_CM * INLIER_CM;
  float cost = 0.0f;
  for (int k = 0; k < m; k++) {
    float dx = px - sxs[k];
    float r = sqrtf(dx * dx + py * py) - ds[k];
    float r2 = r * r;
    cost += (r2 < T2) ? r2 : T2;
  }
  return cost;
}

float x = -1.0f;
float y = -1.0f;

bool tracking = false;
float sx = 0.0f, sy = 0.0f;
unsigned long lastFixMs = 0;
uint8_t pendCount = 0;
float pendX = 0.0f, pendY = 0.0f;
int fixInliers = 0;   // debugging

// d[] = ranges from all 6 sensors (NAN = unavailable). Returns true and a position if
// enough sensors, on at least two different boards, agree on one spot.
bool fitPosition(const float *d, float &outX, float &outY, int &outInliers) {
  float sxs[TOTAL_SENSORS], ds[TOTAL_SENSORS];
  int un[TOTAL_SENSORS];
  int m = 0;
  for (int i = 0; i < TOTAL_SENSORS; i++) {
    if (!validDist(d[i])) continue;
    sxs[m] = SENSOR_X[i];
    ds[m] = d[i] + BODY_RADIUS_CM;
    un[m] = UNIT_OF[i];
    m++;
  }
  if (m < 2) return false;

  // 1) coarse global search for the position most sensors agree on
  const float xMin = -X_MARGIN_CM, xMax = BASELINE_CM + X_MARGIN_CM;
  float bestC = 1e30f, bx = 0.0f, by = 0.0f;
  for (float gx = xMin; gx <= xMax; gx += GRID_CM) {
    for (float gy = 0.0f; gy <= MAX_VALID_CM; gy += GRID_CM) {
      float c = costAt(gx, gy, sxs, ds, m);
      if (tracking) {
        float ddx = gx - sx, ddy = gy - sy;
        c += PRIOR_WEIGHT * (ddx * ddx + ddy * ddy);
      }
      if (c < bestC) { bestC = c; bx = gx; by = gy; }
    }
  }

  // 2) Gauss-Newton refinement using only the sensors that agree (least squares)
  float px = bx, py = by;
  for (int it = 0; it < 4; it++) {
    float a = 0, b = 0, c = 0, g1 = 0, g2 = 0;
    int cnt = 0;
    for (int k = 0; k < m; k++) {
      float dx = px - sxs[k];
      float rho = sqrtf(dx * dx + py * py);
      if (rho < 1.0f) rho = 1.0f;
      float r = rho - ds[k];
      if (fabsf(r) > INLIER_CM) continue;
      float ux = dx / rho, uy = py / rho;
      a += ux * ux; b += ux * uy; c += uy * uy;
      g1 += ux * r; g2 += uy * r;
      cnt++;
    }
    if (cnt < 2) break;
    float det = a * c - b * b;
    if (det < 0.05f) break;                       // sensors nearly in line with the target
    float ddx = -(c * g1 - b * g2) / det;
    float ddy = -(a * g2 - b * g1) / det;
    float step = hypotf(ddx, ddy);
    if (step > 10.0f) { ddx *= 10.0f / step; ddy *= 10.0f / step; step = 10.0f; }
    px += ddx;
    py += ddy;
    if (py < 0.0f) py = 0.0f;
    if (step < 0.2f) break;
  }

  // 3) accept only if enough sensors from at least two different boards agree
  int inl = 0, units = 0;
  bool seen[NUM_UNITS] = {false, false, false};
  for (int k = 0; k < m; k++) {
    float dx = px - sxs[k];
    float r = sqrtf(dx * dx + py * py) - ds[k];
    if (fabsf(r) <= INLIER_CM) {
      inl++;
      if (!seen[un[k]]) { seen[un[k]] = true; units++; }
    }
  }
  outInliers = inl;
  if (units < 2) return false;
  if (inl < (tracking ? MIN_INLIERS_TRACK : MIN_INLIERS_ACQUIRE)) return false;

  outX = px;
  outY = py;
  return true;
}

void getLoc() {
  updateSensors();

  // Snapshot remote data
  float rd[2][SENSORS_PER_UNIT];
  unsigned long remoteAge[2];
  unsigned long now = millis();
  portENTER_CRITICAL(&remoteMux);
  memcpy(rd, remoteDist, sizeof(rd));
  remoteAge[0] = now - lastRemoteUpdateMs[0];
  remoteAge[1] = now - lastRemoteUpdateMs[1];
  portEXIT_CRITICAL(&remoteMux);

  // Assemble all six distances (NAN = unavailable)
  float d[TOTAL_SENSORS];
  for (int s = 0; s < SENSORS_PER_UNIT; s++) {
    d[0 * SENSORS_PER_UNIT + s] = (remoteAge[0] <= REMOTE_TIMEOUT_MS) ? rd[0][s] : NAN;  // left
    d[1 * SENSORS_PER_UNIT + s] = localDist[s];                                          // centre
    d[2 * SENSORS_PER_UNIT + s] = (remoteAge[1] <= REMOTE_TIMEOUT_MS) ? rd[1][s] : NAN;  // right
  }

  float mx = 0.0f, my = 0.0f;
  bool measured = fitPosition(d, mx, my, fixInliers);

  if (measured) {
    if (!tracking || now - lastFixMs > HOLD_MS) {
      // new track
      sx = mx;
      sy = my;
      tracking = true;
      pendCount = 0;
      lastFixMs = now;
    } else {
      float jump = hypotf(mx - sx, my - sy);
      if (jump > MAX_JUMP_CM) {
        // Suspect fix: ignore it unless it keeps showing up in the same new place
        if (pendCount > 0 && hypotf(mx - pendX, my - pendY) <= RELOCK_CLUSTER_CM) pendCount++;
        else pendCount = 1;
        pendX = mx;
        pendY = my;
        if (pendCount >= RELOCK_CYCLES) {
          sx = mx;
          sy = my;
          pendCount = 0;
          lastFixMs = now;
        }
      } else {
        float alpha = constrain(ALPHA_MIN + ALPHA_GAIN * jump, ALPHA_MIN, ALPHA_MAX);
        sx += alpha * (mx - sx);
        sy += alpha * (my - sy);
        pendCount = 0;
        lastFixMs = now;
      }
    }
  }

  if (tracking && now - lastFixMs <= HOLD_MS) {
    x = sx;
    y = sy;
  } else {
    tracking = false;
    x = -1.0f;
    y = -1.0f;
  }
}

// ----------------------------- Java client -----------------------------------
void serviceClient() {
  if (client && client.connected()) return;
  WiFiClient incoming = Server.available();
  if (incoming) {
    client = incoming;
    client.setNoDelay(true);
    Serial.println("Java desktop app connected over WiFi.");
  }
}

void setup() {
  Serial.begin(115200);
  delay(100);

  for (int i = 0; i < SENSORS_PER_UNIT; i++) {
    pinMode(TRIG_PINS[i], OUTPUT);
    pinMode(ECHO_PINS[i], INPUT);
    localDist[i] = NAN;
  }

  WiFi.mode(WIFI_AP_STA);
  WiFi.softAP(ssid, password, WIFI_CHANNEL);
  Serial.print("AP IP address: ");
  Serial.println(WiFi.softAPIP());
  Serial.print("AP MAC (use as receiverMac in the transmitters): ");
  Serial.println(WiFi.softAPmacAddress());

  if (esp_now_init() != ESP_OK) {
    Serial.println("Error initialising ESP-NOW Link");
    return;
  }
  esp_now_register_recv_cb(dataRecv);

  Server.begin();
}

void loop() {
  getLoc();
  serviceClient();

  if (client && client.connected()) {
    client.printf("x: %.1f y: %.1f\n", x, y);
  }

  if (DEBUG_PRINT) {
    static unsigned long lastPrint = 0;
    if (millis() - lastPrint >= DEBUG_INTERVAL_MS) {
      lastPrint = millis();
      Serial.printf("L[%.0f %.0f] C[%.0f %.0f] R[%.0f %.0f]  inl=%d  x=%.1f y=%.1f\n",
                    remoteDist[0][0], remoteDist[0][1],
                    localDist[0], localDist[1],
                    remoteDist[1][0], remoteDist[1][1],
                    fixInliers, x, y);
    }
  }
}