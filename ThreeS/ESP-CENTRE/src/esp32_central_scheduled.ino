#include <Arduino.h>
#include <WiFi.h>
#include <esp_now.h>
#include "esp_wifi.h"

// ============================================================================
//  CENTRE / RECEIVER ESP32  -  time-division ("one sensor at a time") version
//
//  The centre board is now the scheduler. It fires the six ultrasonic sensors
//  strictly one after another, so no sensor can pick up another sensor's ping:
//    - its own two sensors are measured locally
//    - the left and right boards are told WHICH sensor to fire, over ESP-NOW,
//      and reply with the raw distance
//  Run esp32_remote_sensor.ino on the left and right boards.
// ============================================================================

const char* ssid = "ESP32_G34";
const char* password = "123456789";
const int WIFI_CHANNEL = 6;

// STA MAC addresses of the left and right boards (printed by esp32_remote_sensor.ino at boot)
uint8_t leftMac[]  = {0xE0, 0x5A, 0x1B, 0x1F, 0xD9, 0x20};
uint8_t rightMac[] = {0x00, 0x70, 0x07, 0x7C, 0x8B, 0x04};

WiFiServer Server(80);
WiFiClient client;

// ----------------------------- Geometry -------------------------------------
// X position (cm) of every sensor along the front line. MEASURE THESE.
//   Index order: left ESP s0, s1 | centre ESP s0, s1 | right ESP s0, s1
const float BASELINE_CM = 150.0f;                 // distance left corner ESP to right corner ESP
const float CENTRE_X    = BASELINE_CM / 2.0f;     // centre ESP mounting position
const float SENSOR_PITCH_CM = 10.0f;              // spacing between the 2 sensors on one board

const int NUM_UNITS = 3;                          // 0 = left, 1 = centre, 2 = right
const int SENSORS_PER_UNIT = 2;
const int TOTAL_SENSORS = NUM_UNITS * SENSORS_PER_UNIT;

const float SENSOR_X[TOTAL_SENSORS] = {
  0.0f,                           0.0f + SENSOR_PITCH_CM,                  // left unit
  CENTRE_X - SENSOR_PITCH_CM / 2, CENTRE_X + SENSOR_PITCH_CM / 2,          // centre unit
  BASELINE_CM - SENSOR_PITCH_CM,  BASELINE_CM                              // right unit
};

const int UNIT_OF[TOTAL_SENSORS] = {0, 0, 1, 1, 2, 2};

// local (centre board) sensors
const int TRIG_PINS[SENSORS_PER_UNIT] = {18, 12};
const int ECHO_PINS[SENSORS_PER_UNIT] = {19, 14};

const float MIN_VALID_CM = 3.0f;
const float MAX_VALID_CM = 250.0f;
const unsigned long ECHO_TIMEOUT_US = 15000;      // 15 ms = about 2.5 m

// ------------------------- Sensor scheduling (NEW) ---------------------------
// Time between the start of one sensor's ping and the start of the next.
// Must be longer than ECHO_TIMEOUT (15 ms) plus a quiet gap for echoes to die.
// 25 ms -> a full sweep of all 6 sensors takes 150 ms. Try 20 if range allows.
const unsigned long SLOT_MS = 25;

// Order sensors fire in (global index: 0=L0 1=L1 2=C0 3=C1 4=R0 5=R1).
// Alternating boards keeps consecutive pings far apart: L0, C0, R0, L1, C1, R1.
const int SLOT_ORDER[TOTAL_SENSORS] = {0, 2, 4, 1, 3, 5};

// A sensor reading older than this is ignored (about two full sweeps).
const unsigned long SENSOR_STALE_MS = 350;

// Per-sensor median filter. Each sensor is now refreshed only once per sweep, so a
// bigger window adds a lot of lag (window 3 = about 1 extra sweep). 1 = no filter.
const uint8_t FILTER_WINDOW = 1;
const uint8_t FILTER_MIN_VALID = 1;

const unsigned long HOLD_MS = 300;             // keep last position this long after losing it
const float CLUSTER_CM = 15.0f;                // measurments within this of the best one are averaged
const float MAX_JUMP_CM = 60.0f;               // bigger jumps are treated as outliers and are ignored
const uint8_t RELOCK_CYCLES = 3;               // pos only recalculated afger this many updates

// math stuff zzzzz
const float ALPHA_MIN = 0.25f;
const float ALPHA_GAIN = 0.02f;
const float ALPHA_MAX = 0.9f;

const bool DEBUG_PRINT = true;
const unsigned long DEBUG_INTERVAL_MS = 250;

// ESP-NOW messages
typedef struct __attribute__((packed)) TriggerMsg {   // centre -> left/right: "fire this sensor"
  uint8_t sensor;                                     // 0 or 1 on that board
} TriggerMsg;

typedef struct __attribute__((packed)) ResultMsg {    // left/right -> centre: raw reading
  uint8_t sensor;
  float dist;                                         // NAN = nothing seen
} ResultMsg;

inline bool validDist(float d) {
  return !isnan(d) && d >= MIN_VALID_CM && d <= MAX_VALID_CM;
}

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

// Latest filtered distance and arrival time for all six sensors (global index)
SensorFilter sensorFilter[TOTAL_SENSORS];
float sensorDist[TOTAL_SENSORS];
unsigned long sensorStamp[TOTAL_SENSORS];
portMUX_TYPE sensorMux = portMUX_INITIALIZER_UNLOCKED;

// Runs when a left/right board sends back a reading
void dataRecv(const uint8_t *mac_addr, const uint8_t *incomingData, int len) {
  int unitIdx;
  if (memcmp(mac_addr, leftMac, 6) == 0) unitIdx = 0;
  else if (memcmp(mac_addr, rightMac, 6) == 0) unitIdx = 2;
  else return;
  if (len != (int)sizeof(ResultMsg)) return;

  ResultMsg m;
  memcpy(&m, incomingData, sizeof(m));
  if (m.sensor >= SENSORS_PER_UNIT) return;

  int s = unitIdx * SENSORS_PER_UNIT + m.sensor;
  float f = sensorFilter[s].update(validDist(m.dist) ? m.dist : NAN);

  portENTER_CRITICAL(&sensorMux);
  sensorDist[s] = f;
  sensorStamp[s] = millis();
  portEXIT_CRITICAL(&sensorMux);
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

// Fire ONE sensor (global index s). Centre board sensors are measured here;
// for left/right we send a trigger and the reply arrives in dataRecv().
void fireSensor(int s) {
  int unitIdx = UNIT_OF[s];
  int local = s % SENSORS_PER_UNIT;

  if (unitIdx == 1) {
    float f = sensorFilter[s].update(measureDistance(TRIG_PINS[local], ECHO_PINS[local]));
    portENTER_CRITICAL(&sensorMux);
    sensorDist[s] = f;
    sensorStamp[s] = millis();
    portEXIT_CRITICAL(&sensorMux);
  } else {
    TriggerMsg t;
    t.sensor = (uint8_t)local;
    esp_now_send(unitIdx == 0 ? leftMac : rightMac, (uint8_t *)&t, sizeof(t));
  }
}

// ----------------------------- Trilateration ---------------------------------
// Intersection of two circles centred at (p1x,0) and (p2x,0) with radii d1, d2.
// Returns the intersection with y >= 0 (in front of the sensor line).
bool trilaterate(float d1, float d2, float p1x, float p2x, float &outX, float &outY) {
  float D = p2x - p1x;
  if (fabsf(D) < 1.0f) return false;

  float xl = (d1 * d1 - d2 * d2 + D * D) / (2.0f * D);
  float ySq = d1 * d1 - xl * xl;
  if (ySq < -25.0f) return false;   // circles don't meet (allow a little measurement noise)
  if (ySq < 0.0f) ySq = 0.0f;

  outX = p1x + xl;
  outY = sqrtf(ySq);
  return true;
}

float x = -1.0f;
float y = -1.0f;

bool tracking = false;
float sx = 0.0f, sy = 0.0f;
unsigned long lastFixMs = 0;
uint8_t jumpStreak = 0;
int candCount = 0;
float lastD[TOTAL_SENSORS];   // distances used in the last fix (for debug print)

void getLoc() {
  unsigned long now = millis();

  // Take a snapshot of the latest reading from every sensor
  float snapDist[TOTAL_SENSORS];
  unsigned long snapStamp[TOTAL_SENSORS];
  portENTER_CRITICAL(&sensorMux);
  memcpy(snapDist, sensorDist, sizeof(snapDist));
  memcpy(snapStamp, sensorStamp, sizeof(snapStamp));
  portEXIT_CRITICAL(&sensorMux);

  // Assemble all six distances (NAN = unavailable or too old)
  float d[TOTAL_SENSORS];
  for (int i = 0; i < TOTAL_SENSORS; i++) {
    d[i] = (now - snapStamp[i] <= SENSOR_STALE_MS) ? snapDist[i] : NAN;
    lastD[i] = d[i];
  }

  struct Cand { float x, y; };
  Cand c[TOTAL_SENSORS * TOTAL_SENSORS];
  int n = 0;

  for (int i = 0; i < TOTAL_SENSORS; i++) {
    if (!validDist(d[i])) continue;
    for (int j = i + 1; j < TOTAL_SENSORS; j++) {
      if (UNIT_OF[i] == UNIT_OF[j]) continue;
      if (!validDist(d[j])) continue;
      float cx, cy;
      if (trilaterate(d[i], d[j], SENSOR_X[i], SENSOR_X[j], cx, cy)) {
        c[n++] = {cx, cy};
      }
    }
  }
  candCount = n;

  bool measured = false;
  float mx = 0.0f, my = 0.0f;

  if (n > 0) {
    float rx, ry;
    if (tracking) {
      rx = sx;
      ry = sy;
    } else {
      rx = 0.0f;
      ry = 0.0f;
      for (int k = 0; k < n; k++) { rx += c[k].x; ry += c[k].y; }
      rx /= n;
      ry /= n;
    }

    int best = 0;
    float bestD = 1e9f;
    for (int k = 0; k < n; k++) {
      float dd = hypotf(c[k].x - rx, c[k].y - ry);
      if (dd < bestD) { bestD = dd; best = k; }
    }

    float sumX = 0.0f, sumY = 0.0f;
    int cnt = 0;
    for (int k = 0; k < n; k++) {
      if (hypotf(c[k].x - c[best].x, c[k].y - c[best].y) <= CLUSTER_CM) {
        sumX += c[k].x;
        sumY += c[k].y;
        cnt++;
      }
    }
    mx = sumX / cnt;
    my = sumY / cnt;
    measured = true;
  }

  // Smooth / outlier rejector
  if (measured) {
    if (!tracking || now - lastFixMs > HOLD_MS) {
      sx = mx;
      sy = my;
      tracking = true;
      jumpStreak = 0;
      lastFixMs = now;
    } else {
      float jump = hypotf(mx - sx, my - sy);
      if (jump > MAX_JUMP_CM) {
        if (++jumpStreak >= RELOCK_CYCLES) {
          sx = mx;
          sy = my;
          jumpStreak = 0;
          lastFixMs = now;
        }
      } else {
        float alpha = constrain(ALPHA_MIN + ALPHA_GAIN * jump, ALPHA_MIN, ALPHA_MAX);
        sx += alpha * (mx - sx);
        sy += alpha * (my - sy);
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

void addRemotePeer(uint8_t *mac) {
  esp_now_peer_info_t peer = {};
  memcpy(peer.peer_addr, mac, 6);
  peer.channel = WIFI_CHANNEL;
  peer.ifidx = WIFI_IF_AP;       // we transmit from the soft-AP interface
  peer.encrypt = false;
  if (esp_now_add_peer(&peer) != ESP_OK) {
    Serial.println("Failed to add ESP-NOW peer");
  }
}

void setup() {
  Serial.begin(115200);
  delay(100);

  for (int i = 0; i < SENSORS_PER_UNIT; i++) {
    pinMode(TRIG_PINS[i], OUTPUT);
    pinMode(ECHO_PINS[i], INPUT);
  }
  for (int i = 0; i < TOTAL_SENSORS; i++) {
    sensorDist[i] = NAN;
    sensorStamp[i] = 0;
    lastD[i] = NAN;
  }

  WiFi.mode(WIFI_AP_STA);
  WiFi.softAP(ssid, password, WIFI_CHANNEL);
  Serial.print("AP IP address: ");
  Serial.println(WiFi.softAPIP());
  Serial.print("AP MAC (use as centralMac in esp32_remote_sensor.ino): ");
  Serial.println(WiFi.softAPmacAddress());

  if (esp_now_init() != ESP_OK) {
    Serial.println("Error initialising ESP-NOW Link");
    return;
  }
  esp_now_register_recv_cb(dataRecv);
  addRemotePeer(leftMac);
  addRemotePeer(rightMac);

  Server.begin();
}

unsigned long lastSlotMs = 0;
uint8_t slotPos = 0;

void loop() {
  serviceClient();

  unsigned long now = millis();
  if (now - lastSlotMs < SLOT_MS) return;
  lastSlotMs = now;

  // 1) work out the position from everything received so far and send it on
  getLoc();
  if (client && client.connected()) {
    client.printf("x: %.1f y: %.1f\n", x, y);
  }

  if (DEBUG_PRINT) {
    static unsigned long lastPrint = 0;
    if (now - lastPrint >= DEBUG_INTERVAL_MS) {
      lastPrint = now;
      Serial.printf("L[%.0f %.0f] C[%.0f %.0f] R[%.0f %.0f]  cands=%d  x=%.1f y=%.1f\n",
                    lastD[0], lastD[1], lastD[2], lastD[3], lastD[4], lastD[5],
                    candCount, x, y);
    }
  }

  // 2) fire the NEXT sensor in the schedule (only one sensor pings at a time)
  fireSensor(SLOT_ORDER[slotPos]);
  slotPos = (slotPos + 1) % TOTAL_SENSORS;
}
