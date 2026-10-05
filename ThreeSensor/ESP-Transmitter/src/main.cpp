// ============================================================================
//  CORNER TRANSMITTER  (ESP #2 and #3 of 3)
//  Flash the SAME code to both the LEFT and RIGHT corner ESPs.
//  The centre receiver tells them apart by their MAC address, so the only
//  thing to configure is the centre receiver's softAP MAC below.
//  Each unit has 2 ultrasonic sensors facing into the play area.
// ============================================================================
#include <Arduino.h>
#include <esp_now.h>
#include <WiFi.h>
#include "esp_wifi.h"

// softAP MAC of the CENTRE receiver (it prints this on boot as "AP MAC ...")
// 4C:C3:82:0C:2D:99
uint8_t receiverMac[] = {0x4C, 0xC3, 0x82, 0x0C, 0x2D, 0x99};

const int WIFI_CHANNEL = 6;   // must match the centre receiver's AP channel

const int NUM_SENSORS = 2;
const int TRIG_PINS[NUM_SENSORS] = {18, 16};
const int ECHO_PINS[NUM_SENSORS] = {19, 17};

const float MIN_VALID_CM = 3.0f;
const float MAX_VALID_CM = 250.0f;
const unsigned long ECHO_TIMEOUT_US = 15000;
const unsigned long SENSOR_GAP_MS = 5;

const uint8_t FILTER_WINDOW = 5;
const uint8_t FILTER_MIN_VALID = 3;

typedef struct StructMessage {
  float dist[NUM_SENSORS];   
} StructMessage;

StructMessage message;

volatile uint32_t sendStartUs = 0;
volatile uint32_t lastElapsedUs = 0;
volatile bool sendDone = false;
volatile bool sendOk = false;

void dataSent(const uint8_t *mac_addr, esp_now_send_status_t status) {
  lastElapsedUs = micros() - sendStartUs;
  sendOk = (status == ESP_NOW_SEND_SUCCESS);
  sendDone = true;
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

SensorFilter filters[NUM_SENSORS];

float measureDistance(int trigPin, int echoPin) {
  digitalWrite(trigPin, LOW);
  delayMicroseconds(2);
  digitalWrite(trigPin, HIGH);
  delayMicroseconds(10);
  digitalWrite(trigPin, LOW);

  unsigned long duration = pulseIn(echoPin, HIGH, ECHO_TIMEOUT_US);
  if (duration == 0) return NAN;

  float cm = duration * 0.0343f / 2.0f;
  return (cm >= MIN_VALID_CM && cm <= MAX_VALID_CM) ? cm : NAN;
}

void updateSensors() {
  for (int i = 0; i < NUM_SENSORS; i++) {
    float f = filters[i].update(measureDistance(TRIG_PINS[i], ECHO_PINS[i]));
    message.dist[i] = isnan(f) ? -1.0f : f;
    if (i < NUM_SENSORS - 1) delay(SENSOR_GAP_MS);
  }
}

void setup() {
  Serial.begin(115200);

  WiFi.mode(WIFI_STA);
  WiFi.disconnect();

  esp_wifi_set_promiscuous(true);
  esp_err_t chErr = esp_wifi_set_channel(WIFI_CHANNEL, WIFI_SECOND_CHAN_NONE);
  esp_wifi_set_promiscuous(false);

  uint8_t ch; wifi_second_chan_t sc;
  esp_wifi_get_channel(&ch, &sc);
  Serial.printf("set_channel=%d, channel now=%u\n", chErr, ch);
  Serial.print("This unit's MAC (put in the centre receiver's leftMac/rightMac): ");
  Serial.println(WiFi.macAddress());

  for (int i = 0; i < NUM_SENSORS; i++) {
    pinMode(TRIG_PINS[i], OUTPUT);
    pinMode(ECHO_PINS[i], INPUT);
    message.dist[i] = -1.0f;
  }

  if (esp_now_init() != ESP_OK) {
    Serial.println("Error initialising ESP-NOW");
    return;
  }

  esp_now_register_send_cb(dataSent);

  esp_now_peer_info_t peerInfo;
  memset(&peerInfo, 0, sizeof(peerInfo));
  memcpy(peerInfo.peer_addr, receiverMac, 6);
  peerInfo.channel = WIFI_CHANNEL;
  peerInfo.encrypt = false;

  if (esp_now_add_peer(&peerInfo) != ESP_OK) {
    Serial.println("Failed to add peer");
    return;
  }
}

void loop() {
  updateSensors();

  sendDone = false;
  sendStartUs = micros();
  esp_err_t outcome = esp_now_send(receiverMac, (uint8_t *)&message, sizeof(message));
  if (outcome != ESP_OK) {
    Serial.printf("Error sending the message (err=%d)\n", outcome);
  }

  // Wait briefly for the callback so we can report this send's result
  unsigned long waitStart = millis();
  while (!sendDone && millis() - waitStart < 50) {
    delay(1);
  }

  if (sendDone) {
    if (sendOk) {
      Serial.printf("Delivery OK - %lu us (%.2f ms)\n",
                    (unsigned long)lastElapsedUs, lastElapsedUs / 1000.0f);
    } else {
      Serial.printf("Delivery fail - %lu us\n", (unsigned long)lastElapsedUs);
    }
  } else {
    Serial.println("No send callback within 50 ms");
  }
}