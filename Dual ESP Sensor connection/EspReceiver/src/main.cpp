#include <Arduino.h>
#include <WiFi.h>
#include <esp_now.h>
#include "esp_wifi.h"

const char* ssid = "ESP32_G34";
const char* password = "123456789";
const int WIFI_CHANNEL = 6;


uint8_t transmitterMac[] = {0xE0, 0x5A, 0x1B, 0x1F, 0xD9, 0x20};

WiFiServer Server(80);
WiFiClient client;

const int NUM_SENSORS = 3;
const float BASELINE_CM = 150.0f;
const float LOCAL_X_OFF[NUM_SENSORS]  = {0.0f, 0.0f, 0.0f};
const float REMOTE_X_OFF[NUM_SENSORS] = {0.0f, 0.0f, 0.0f};

const int TRIG_PINS[NUM_SENSORS] = {18, 16, 12};
const int ECHO_PINS[NUM_SENSORS] = {19, 17, 13};

const float MIN_VALID_CM = 3.0f;
const float MAX_VALID_CM = 250.0f;
const unsigned long ECHO_TIMEOUT_US = 15000;   
const unsigned long SENSOR_GAP_MS = 5;         


const uint8_t FILTER_WINDOW = 5;
const uint8_t FILTER_MIN_VALID = 3;


const unsigned long REMOTE_TIMEOUT_MS = 300;   
const unsigned long HOLD_MS = 300;             
const float CLUSTER_CM = 15.0f;                
const float MAX_JUMP_CM = 60.0f;               
const uint8_t RELOCK_CYCLES = 3;               
const float ALPHA_MIN = 0.25f;                 
const float ALPHA_GAIN = 0.02f;                
const float ALPHA_MAX = 0.9f;                  

const bool DEBUG_PRINT = true;
const unsigned long DEBUG_INTERVAL_MS = 250;
typedef struct StructMessage {
  float dist[NUM_SENSORS];   
} StructMessage;

portMUX_TYPE remoteMux = portMUX_INITIALIZER_UNLOCKED;
float remoteDist[NUM_SENSORS] = {-1.0f, -1.0f, -1.0f};
unsigned long lastRemoteUpdateMs = 0;

void dataRecv(const uint8_t *mac_addr, const uint8_t *incomingData, int len) {
  if (memcmp(mac_addr, transmitterMac, 6) != 0) return;
  if (len != (int)sizeof(StructMessage)) return;

  StructMessage m;
  memcpy(&m, incomingData, sizeof(m));

  
  portENTER_CRITICAL(&remoteMux);
  memcpy(remoteDist, m.dist, sizeof(remoteDist));
  lastRemoteUpdateMs = millis();
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

SensorFilter localFilter[NUM_SENSORS];
float localDist[NUM_SENSORS];

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
  for (int i = 0; i < NUM_SENSORS; i++) {
    localDist[i] = localFilter[i].update(measureDistance(TRIG_PINS[i], ECHO_PINS[i]));
    if (i < NUM_SENSORS - 1) delay(SENSOR_GAP_MS);
  }
}

bool trilaterate(float d1, float d2, float p1x, float p2x, float &outX, float &outY) {
  float D = p2x - p1x;
  if (fabsf(D) < 1.0f) return false;

  float xl = (d1 * d1 - d2 * d2 + D * D) / (2.0f * D);
  float ySq = d1 * d1 - xl * xl;
  if (ySq < -25.0f) return false;   
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
int candCount = 0;                  // debugging

void getLoc() {
  updateSensors();

  
  float rd[NUM_SENSORS];
  unsigned long remoteAge;
  portENTER_CRITICAL(&remoteMux);
  memcpy(rd, remoteDist, sizeof(rd));
  remoteAge = millis() - lastRemoteUpdateMs;
  portEXIT_CRITICAL(&remoteMux);
  bool remoteFresh = remoteAge <= REMOTE_TIMEOUT_MS;

  unsigned long now = millis();

  
  struct Cand { float x, y; };
  Cand c[NUM_SENSORS * NUM_SENSORS];
  int n = 0;

  if (remoteFresh) {
    for (int i = 0; i < NUM_SENSORS; i++) {
      if (!validDist(localDist[i])) continue;
      for (int j = 0; j < NUM_SENSORS; j++) {
        if (!validDist(rd[j])) continue;
        float cx, cy;
        if (trilaterate(localDist[i], rd[j], LOCAL_X_OFF[i], BASELINE_CM + REMOTE_X_OFF[j], cx, cy)) {
          c[n++] = {cx, cy};
        }
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
      float d = hypotf(c[k].x - rx, c[k].y - ry);
      if (d < bestD) { bestD = d; best = k; }
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

  for (int i = 0; i < NUM_SENSORS; i++) {
    pinMode(TRIG_PINS[i], OUTPUT);
    pinMode(ECHO_PINS[i], INPUT);
    localDist[i] = NAN;
  }

  WiFi.mode(WIFI_AP_STA);
  WiFi.softAP(ssid, password, WIFI_CHANNEL);
  Serial.print("AP IP address: ");
  Serial.println(WiFi.softAPIP());

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
      Serial.printf("L[%.0f %.0f %.0f] R[%.0f %.0f %.0f] pairs=%d  x=%.1f y=%.1f\n",
                    localDist[0], localDist[1], localDist[2],
                    remoteDist[0], remoteDist[1], remoteDist[2],
                    candCount, x, y);
    }
  }
}