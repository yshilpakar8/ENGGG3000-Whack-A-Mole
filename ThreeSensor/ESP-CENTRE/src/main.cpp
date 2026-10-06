#include <Arduino.h>
#include <WiFi.h>
#include <esp_now.h>
#include "esp_wifi.h"

const char* ssid = "ESP32_G34";
const char* password = "123456789";
const int WIFI_CHANNEL = 6;

uint8_t leftMac[]  = {0xE0, 0x5A, 0x1B, 0x1F, 0xD9, 0x20};
uint8_t rightMac[] = {0x00, 0x70, 0x07, 0x7C, 0x8B, 0x04};

WiFiServer Server(80);
WiFiClient client;

// ----------------------------- Geometry -------------------------------------
// X position (cm) of every sensor along the front line. MEASURE THESE.
//   Index order: left ESP s0, s1 | centre ESP s0, s1 | right ESP s0, s1
const float BASELINE_CM = 150.0f;                 // distance left corner ESP to right corner ESP
const float CENTRE_X    = BASELINE_CM / 2.0f;     // centre ESP mounting position
const float SENSOR_PITCH_CM = 5.0f;              // spacing between the 2 sensors on one board

const int NUM_UNITS = 3;                          // 0 = left, 1 = centre, 2 = right
const int SENSORS_PER_UNIT = 2;
const int TOTAL_SENSORS = NUM_UNITS * SENSORS_PER_UNIT;

const float SENSOR_X[TOTAL_SENSORS] = {
  0.0f,                           0.0f + SENSOR_PITCH_CM,                  // left unit
  CENTRE_X - SENSOR_PITCH_CM / 2, CENTRE_X + SENSOR_PITCH_CM / 2,          // centre unit
  BASELINE_CM - SENSOR_PITCH_CM,  BASELINE_CM                              // right unit
};

const int UNIT_OF[TOTAL_SENSORS] = {0, 0, 1, 1, 2, 2};

// local sensors
const int TRIG_PINS[SENSORS_PER_UNIT] = {18, 12};
const int ECHO_PINS[SENSORS_PER_UNIT] = {19, 14};

const float MIN_VALID_CM = 3.0f;
const float MAX_VALID_CM = 250.0f;
const unsigned long ECHO_TIMEOUT_US = 15000;
const unsigned long SENSOR_GAP_MS = 5;

const uint8_t FILTER_WINDOW = 5;
const uint8_t FILTER_MIN_VALID = 3;

// ----------------------------- Tracking tuning -------------------------------
const unsigned long REMOTE_TIMEOUT_MS = 150;   // remote data older than this is ignored (must be > 2x the transmitters' send period)
const unsigned long HOLD_MS = 300;             // keep last position this long after losing it
const float CLUSTER_CM = 15.0f;                // candidates within this of each other count as agreeing
const float MAX_JUMP_CM = 20.0f;               // bigger jumps are treated as outliers and are ignored
const uint8_t RELOCK_CYCLES = 3;               // consecutive "far away" fixes needed before snapping to them

// Geometry quality: a sensor pair is only trusted when its two circles cross at a
// decent angle. sin(angle) near 0 means tiny range errors become huge position errors.
const float MIN_GEOM_SIN = 0.30f;              // ~17 degrees. Raise if still noisy, lower if you lose coverage
const float TRACK_PRIOR_CM = 40.0f;            // candidates near the current track are favoured (smaller = stickier)
const uint8_t ACQUIRE_MIN_CANDS = 2;           // agreeing candidates needed to START tracking (set 1 if targets are missed)

// One Euro filter (smooths hard when still, follows quickly when moving)
const float MIN_CUTOFF_HZ = 1.5f;              // LOWER = less jitter when still, more lag
const float BETA          = 0.04f;             // HIGHER = less lag when moving fast, more jitter while moving
const float D_CUTOFF_HZ   = 1.0f;              // smoothing of the speed estimate; rarely needs changing

const bool DEBUG_PRINT = true;
const unsigned long DEBUG_INTERVAL_MS = 250;

// ESP-NOW payload
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

// ----------------------------- Per-sensor median filter ----------------------
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

// ----------------------------- One Euro filter -------------------------------
struct OneEuro {
  bool init = false;
  float xPrev = 0.0f;    // last filtered value
  float dxPrev = 0.0f;   // last filtered speed
  float rawPrev = 0.0f;  // last raw value

  static float alphaFor(float cutoffHz, float dt) {
    float tau = 1.0f / (2.0f * PI * cutoffHz);
    return 1.0f / (1.0f + tau / dt);
  }

  void reset() { init = false; }

  float filter(float v, float dt) {
    if (!init) {
      init = true;
      xPrev = rawPrev = v;
      dxPrev = 0.0f;
      return v;
    }
    float dx = (v - rawPrev) / dt;
    rawPrev = v;
    dxPrev += alphaFor(D_CUTOFF_HZ, dt) * (dx - dxPrev);

    float cutoff = MIN_CUTOFF_HZ + BETA * fabsf(dxPrev);
    xPrev += alphaFor(cutoff, dt) * (v - xPrev);
    return xPrev;
  }
};

OneEuro filtX, filtY;

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

// ----------------------------- Trilateration ---------------------------------
// Intersection of two circles centred at (p1x,0) and (p2x,0) with radii d1, d2.
// Returns the intersection with y >= 0 (in front of the sensor line), plus
// outSin = sine of the angle between the two range vectors (geometry quality,
// 1 = ideal, 0 = useless).
bool trilaterate(float d1, float d2, float p1x, float p2x,
                 float &outX, float &outY, float &outSin) {
  float D = p2x - p1x;
  if (fabsf(D) < 1.0f) return false;

  float xl = (d1 * d1 - d2 * d2 + D * D) / (2.0f * D);
  float ySq = d1 * d1 - xl * xl;
  if (ySq < -25.0f) return false;   // circles don't meet (allow a little measurement noise)
  if (ySq < 0.0f) ySq = 0.0f;

  outX = p1x + xl;
  outY = sqrtf(ySq);

  // unit vectors from each sensor to the point; |cross product| = sin(angle between them)
  float ux1 = (outX - p1x) / d1, uy1 = outY / d1;
  float ux2 = (outX - p2x) / d2, uy2 = outY / d2;
  outSin = fabsf(ux1 * uy2 - ux2 * uy1);
  return true;
}

float x = -1.0f;
float y = -1.0f;

bool tracking = false;
float sx = 0.0f, sy = 0.0f;
unsigned long lastFixMs = 0;
unsigned long lastUpdateMs = 0;
uint8_t jumpStreak = 0;
int candCount = 0;

void getLoc() {
  updateSensors();

  float rd[2][SENSORS_PER_UNIT];
  unsigned long remoteAge[2];
  unsigned long now = millis();
  portENTER_CRITICAL(&remoteMux);
  memcpy(rd, remoteDist, sizeof(rd));
  remoteAge[0] = now - lastRemoteUpdateMs[0];
  remoteAge[1] = now - lastRemoteUpdateMs[1];
  portEXIT_CRITICAL(&remoteMux);

  // real time step since the last update (loop time varies with echo timeouts)
  float dt = (lastUpdateMs == 0) ? 0.03f : (now - lastUpdateMs) / 1000.0f;
  lastUpdateMs = now;
  dt = constrain(dt, 0.005f, 0.25f);

  // Assemble all six distances (NAN = unavailable)
  float d[TOTAL_SENSORS];
  for (int s = 0; s < SENSORS_PER_UNIT; s++) {
    d[0 * SENSORS_PER_UNIT + s] = (remoteAge[0] <= REMOTE_TIMEOUT_MS) ? rd[0][s] : NAN;  // left
    d[1 * SENSORS_PER_UNIT + s] = localDist[s];                                          // centre
    d[2 * SENSORS_PER_UNIT + s] = (remoteAge[1] <= REMOTE_TIMEOUT_MS) ? rd[1][s] : NAN;  // right
  }

  // Candidate positions from every cross-board sensor pair, each with a quality weight
  struct Cand { float x, y, w; };
  Cand c[TOTAL_SENSORS * TOTAL_SENSORS];
  int n = 0;

  for (int i = 0; i < TOTAL_SENSORS; i++) {
    if (!validDist(d[i])) continue;
    for (int j = i + 1; j < TOTAL_SENSORS; j++) {
      if (UNIT_OF[i] == UNIT_OF[j]) continue;
      if (!validDist(d[j])) continue;
      float cx, cy, sn;
      if (trilaterate(d[i], d[j], SENSOR_X[i], SENSOR_X[j], cx, cy, sn)) {
        if (sn < MIN_GEOM_SIN) continue;     // ill-conditioned pair, skip it
        c[n++] = {cx, cy, sn * sn};          // position error ~ 1/sin, so weight ~ sin^2
      }
    }
  }
  candCount = n;

  bool measured = false;
  float mx = 0.0f, my = 0.0f;

  if (n > 0) {
    // Pick the candidate with the most (quality-weighted) agreement from the others,
    // nudged toward the current track so it doesn't hop to a ghost intersection.
    int best = 0;
    float bestScore = -1.0f;
    for (int k = 0; k < n; k++) {
      float support = 0.0f;
      for (int j = 0; j < n; j++) {
        if (hypotf(c[j].x - c[k].x, c[j].y - c[k].y) <= CLUSTER_CM) support += c[j].w;
      }
      float score = support;
      if (tracking) score /= 1.0f + hypotf(c[k].x - sx, c[k].y - sy) / TRACK_PRIOR_CM;
      if (score > bestScore) { bestScore = score; best = k; }
    }

    // Quality-weighted mean of the winning cluster
    float sumW = 0.0f, sumX = 0.0f, sumY = 0.0f;
    int cnt = 0;
    for (int k = 0; k < n; k++) {
      if (hypotf(c[k].x - c[best].x, c[k].y - c[best].y) <= CLUSTER_CM) {
        sumW += c[k].w;
        sumX += c[k].w * c[k].x;
        sumY += c[k].w * c[k].y;
        cnt++;
      }
    }
    mx = sumX / sumW;
    my = sumY / sumW;

    // don't start tracking off a lone, unconfirmed candidate
    measured = tracking || cnt >= ACQUIRE_MIN_CANDS;
  }

  // Outlier rejection + One Euro smoothing
  if (measured) {
    if (!tracking || now - lastFixMs > HOLD_MS) {
      filtX.reset();
      filtY.reset();
      sx = filtX.filter(mx, dt);
      sy = filtY.filter(my, dt);
      tracking = true;
      jumpStreak = 0;
      lastFixMs = now;
    } else {
      float jump = hypotf(mx - sx, my - sy);
      if (jump > MAX_JUMP_CM) {
        if (++jumpStreak >= RELOCK_CYCLES) {   // it really did move: snap to it
          filtX.reset();
          filtY.reset();
          sx = filtX.filter(mx, dt);
          sy = filtY.filter(my, dt);
          jumpStreak = 0;
          lastFixMs = now;
        }
      } else {
        sx = filtX.filter(mx, dt);
        sy = filtY.filter(my, dt);
        jumpStreak = 0;
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
      Serial.printf("L[%.0f %.0f] C[%.0f %.0f] R[%.0f %.0f]  cands=%d  x=%.1f y=%.1f\n",
                    remoteDist[0][0], remoteDist[0][1],
                    localDist[0], localDist[1],
                    remoteDist[1][0], remoteDist[1][1],
                    candCount, x, y);
    }
  }
}