#include <Arduino.h>
#include <WiFi.h>
#include <esp_now.h>
#include "esp_wifi.h"

const char* ssid = "ESP32_G34";
const char* password = "123456789";
const int WIFI_CHANNEL = 6;

uint8_t leftMac[]  = {0xE0, 0x5A, 0x1B, 0x1F, 0xD9, 0x20}; // Update with your Left ESP MAC
uint8_t rightMac[] = {0x00, 0x70, 0x07, 0x7C, 0x8B, 0x04}; // Update with your Right ESP MAC

WiFiServer Server(80);
WiFiClient client;

// ----------------------------- Geometry & Angles -----------------------------
const float BASELINE_CM = 150.0f;                 
const float CENTRE_X    = BASELINE_CM / 2.0f;     
const float SENSOR_PITCH_CM = 10.0f;              

const int NUM_UNITS = 3;                          // 0 = left, 1 = centre, 2 = right
const int SENSORS_PER_UNIT = 2;
const int TOTAL_SENSORS = NUM_UNITS * SENSORS_PER_UNIT;

// Sensor X positions along the front baseline (Y = 0)
const float SENSOR_X[TOTAL_SENSORS] = {
  0.0f,                           0.0f + SENSOR_PITCH_CM,                  // left unit (Outer, Inner)
  CENTRE_X - SENSOR_PITCH_CM / 2, CENTRE_X + SENSOR_PITCH_CM / 2,          // centre unit (s0, s1)
  BASELINE_CM - SENSOR_PITCH_CM,  BASELINE_CM                              // right unit (Inner, Outer)
};

// Sensor pointing angles (degrees counter-clockwise from +X axis) relative to player
const float SENSOR_ANGLE_DEG[TOTAL_SENSORS] = {
  90.0f,  // Left Outer: straight out
  45.0f,  // Left Inner: angled toward center
  90.0f,  // Centre s0: straight out
  90.0f,  // Centre s1: straight out
  135.0f, // Right Inner: angled toward center
  90.0f   // Right Outer: straight out
};

const int UNIT_OF[TOTAL_SENSORS] = {0, 0, 1, 1, 2, 2};

// Local sensors (Centre unit)
const int TRIG_PINS[SENSORS_PER_UNIT] = {18, 12};
const int ECHO_PINS[SENSORS_PER_UNIT] = {19, 14};

const float MIN_VALID_CM = 3.0f;
const float MAX_VALID_CM = 250.0f;
const unsigned long ECHO_TIMEOUT_US = 15000;
const unsigned long SENSOR_GAP_MS = 5;

const uint8_t FILTER_WINDOW = 5;
const uint8_t FILTER_MIN_VALID = 3;

const unsigned long REMOTE_TIMEOUT_MS = 400;   
const unsigned long HOLD_MS = 300;             
const float CLUSTER_CM = 25.0f;                
const float MAX_JUMP_CM = 50.0f;               
const uint8_t RELOCK_CYCLES = 3;               

const float ALPHA_MIN = 0.3f;
const float ALPHA_GAIN = 0.02f;
const float ALPHA_MAX = 0.85f;

const bool DEBUG_PRINT = true;
const unsigned long DEBUG_INTERVAL_MS = 250;

// ESP-NOW payloads
typedef struct SensorMessage {
  float dist[SENSORS_PER_UNIT];
} SensorMessage;

typedef struct CommandMessage {
  uint8_t command;
} CommandMessage;

portMUX_TYPE remoteMux = portMUX_INITIALIZER_UNLOCKED;
float remoteDist[2][SENSORS_PER_UNIT] = {{-1.0f, -1.0f}, {-1.0f, -1.0f}}; 
unsigned long lastRemoteUpdateMs[2] = {0, 0};
volatile bool remoteDataReady[2] = {false, false};

void dataRecv(const uint8_t *mac_addr, const uint8_t *incomingData, int len) {
  int slot = -1;
  if (memcmp(mac_addr, leftMac, 6) == 0) slot = 0;
  else if (memcmp(mac_addr, rightMac, 6) == 0) slot = 1;
  else return;
  if (len != (int)sizeof(SensorMessage)) return;

  SensorMessage m;
  memcpy(&m, incomingData, sizeof(m));

  portENTER_CRITICAL(&remoteMux);
  memcpy(remoteDist[slot], m.dist, sizeof(m.dist));
  lastRemoteUpdateMs[slot] = millis();
  remoteDataReady[slot] = true;
  portEXIT_CRITICAL(&remoteMux);
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

SensorFilter localFilter[SENSORS_PER_UNIT];
float localDist[SENSORS_PER_UNIT] = {NAN, NAN};

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
  if (duration == 0) return NAN;

  float cm = duration * 0.0343f / 2.0f;
  return validDist(cm) ? cm : NAN;
}

void updateLocalSensors() {
  for (int i = 0; i < SENSORS_PER_UNIT; i++) {
    localDist[i] = localFilter[i].update(measureDistance(TRIG_PINS[i], ECHO_PINS[i]));
    if (i < SENSORS_PER_UNIT - 1) delay(SENSOR_GAP_MS);
  }
}

void pollRemoteUnits() {
  CommandMessage cmd = {1};
  
  // Poll Left Unit
  remoteDataReady[0] = false;
  esp_now_send(leftMac, (uint8_t *)&cmd, sizeof(cmd));
  unsigned long t0 = millis();
  while (!remoteDataReady[0] && millis() - t0 < 60) { delay(2); }

  // Poll Right Unit
  remoteDataReady[1] = false;
  esp_now_send(rightMac, (uint8_t *)&cmd, sizeof(cmd));
  t0 = millis();
  while (!remoteDataReady[1] && millis() - t0 < 60) { delay(2); }
}

bool trilaterate(float d1, float d2, float p1x, float p2x, float &outX, float &outY) {
  float D = p2x - p1x;
  if (fabsf(D) < 1.0f) return false;

  float xl = (d1 * d1 - d2 * d2 + D * D) / (2.0f * D);
  float ySq = d1 * d1 - xl * xl;
  if (ySq < -50.0f) return false;
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

void getLoc() {
  // 1. Poll remote units (outer/corner sensors)
  pollRemoteUnits();

  float rd[2][SENSORS_PER_UNIT];
  unsigned long remoteAge[2];
  unsigned long now = millis();
  portENTER_CRITICAL(&remoteMux);
  memcpy(rd, remoteDist, sizeof(rd));
  remoteAge[0] = now - lastRemoteUpdateMs[0];
  remoteAge[1] = now - lastRemoteUpdateMs[1];
  portEXIT_CRITICAL(&remoteMux);

  float d[TOTAL_SENSORS];
  for (int s = 0; s < SENSORS_PER_UNIT; s++) {
    d[0 * SENSORS_PER_UNIT + s] = (remoteAge[0] <= REMOTE_TIMEOUT_MS) ? rd[0][s] : NAN;  // left
    d[1 * SENSORS_PER_UNIT + s] = NAN;                                                   // centre (dormant)
    d[2 * SENSORS_PER_UNIT + s] = (remoteAge[1] <= REMOTE_TIMEOUT_MS) ? rd[1][s] : NAN;  // right
  }

  struct Cand { float x, y; };
  Cand c[TOTAL_SENSORS * TOTAL_SENSORS];
  int n = 0;

  // Multi-unit cross trilateration
  for (int i = 0; i < TOTAL_SENSORS; i++) {
    if (UNIT_OF[i] == 1) continue;
    if (!validDist(d[i])) continue;
    for (int j = i + 1; j < TOTAL_SENSORS; j++) {
      if (UNIT_OF[j] == 1) continue;
      if (UNIT_OF[i] == UNIT_OF[j]) continue;
      if (!validDist(d[j])) continue;
      float cx, cy;
      if (trilaterate(d[i], d[j], SENSOR_X[i], SENSOR_X[j], cx, cy)) {
        if (cy >= 0.0f) {
          c[n++] = {cx, cy};
        }
      }
    }
  }

  // Single-box ray projection fallback for outer units when cross-trilateration misses
  if (n == 0) {
    for (int u = 0; u < TOTAL_SENSORS; u += 2) {
      if (u == 2) continue; // Skip centre unit
      if (validDist(d[u]) && validDist(d[u+1])) {
        float rad0 = SENSOR_ANGLE_DEG[u] * (PI / 180.0f);
        float rad1 = SENSOR_ANGLE_DEG[u+1] * (PI / 180.0f);
        float px0 = SENSOR_X[u] + d[u] * cosf(rad0);
        float py0 = d[u] * sinf(rad0);
        float px1 = SENSOR_X[u+1] + d[u+1] * cosf(rad1);
        float py1 = d[u+1] * sinf(rad1);
        c[n++] = {(px0 + px1) / 2.0f, (py0 + py1) / 2.0f};
      } else if (validDist(d[u])) {
        float rad = SENSOR_ANGLE_DEG[u] * (PI / 180.0f);
        c[n++] = {SENSOR_X[u] + d[u] * cosf(rad), d[u] * sinf(rad)};
      } else if (validDist(d[u+1])) {
        float rad = SENSOR_ANGLE_DEG[u+1] * (PI / 180.0f);
        c[n++] = {SENSOR_X[u+1] + d[u+1] * cosf(rad), d[u+1] * sinf(rad)};
      }
    }
  }

  candCount = n;
  bool measured = false;
  float mx = 0.0f, my = 0.0f;

  if (n > 0) {
    // Outer sensors succeeded. Centre sensors stay dormant.
    localDist[0] = NAN;
    localDist[1] = NAN;

    float rx = tracking ? sx : CENTRE_X;
    float ry = tracking ? sy : 50.0f;

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
  } else {
    // Fallback to centre sensor logic
    updateLocalSensors();
    bool v0 = validDist(localDist[0]);
    bool v1 = validDist(localDist[1]);

    if (v0 && v1) {
      my = (localDist[0] + localDist[1]) / 2.0f;
      mx = CENTRE_X;
      measured = true;
    } else if (v0) {
      my = localDist[0];
      mx = CENTRE_X;
      measured = true;
    } else if (v1) {
      my = localDist[1];
      mx = CENTRE_X;
      measured = true;
    }
  }

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

  esp_now_peer_info_t peerInfo;
  
  memset(&peerInfo, 0, sizeof(peerInfo));
  memcpy(peerInfo.peer_addr, leftMac, 6);
  peerInfo.channel = WIFI_CHANNEL;
  peerInfo.encrypt = false;
  if (esp_now_add_peer(&peerInfo) != ESP_OK) {
    Serial.println("Failed to add Left peer");
  }

  memset(&peerInfo, 0, sizeof(peerInfo));
  memcpy(peerInfo.peer_addr, rightMac, 6);
  peerInfo.channel = WIFI_CHANNEL;
  peerInfo.encrypt = false;
  if (esp_now_add_peer(&peerInfo) != ESP_OK) {
    Serial.println("Failed to add Right peer");
  }

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