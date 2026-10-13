#include <Arduino.h>
#include <WiFi.h>
#include <esp_now.h>
#include "esp_wifi.h"

// ============================================================================
//  LEFT / RIGHT SENSOR BOARD  -  fires only when the centre board says so
//
//  Flash the SAME sketch to both the left and the right board. The centre board
//  tells each one which of its two sensors to fire (so no two sensors ever ping
//  at the same time) and this board replies with the raw distance.
//
//  You did not send me your original left/right sketch, so check the pins below
//  against it before flashing.
// ============================================================================

const int WIFI_CHANNEL = 6;

// AP MAC of the centre board: it prints "AP MAC (use as centralMac ...)" at boot.
uint8_t centralMac[] = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00};   // <-- REPLACE with the centre's AP MAC

const int SENSORS = 2;
const int TRIG_PINS[SENSORS] = {18, 12};   // <-- check against your wiring
const int ECHO_PINS[SENSORS] = {19, 14};

const float MIN_VALID_CM = 3.0f;
const float MAX_VALID_CM = 250.0f;
const unsigned long ECHO_TIMEOUT_US = 15000;

typedef struct __attribute__((packed)) TriggerMsg {   // centre -> this board
  uint8_t sensor;
} TriggerMsg;

typedef struct __attribute__((packed)) ResultMsg {    // this board -> centre
  uint8_t sensor;
  float dist;                                         // NAN = nothing seen
} ResultMsg;

volatile int8_t pendingSensor = -1;   // set by the receive callback, handled in loop()

// Runs when the centre board asks for a measurement. Only records the request;
// the actual (blocking) measurement happens in loop(), not inside the callback.
void onTrigger(const uint8_t *mac_addr, const uint8_t *incomingData, int len) {
  if (memcmp(mac_addr, centralMac, 6) != 0) return;
  if (len != (int)sizeof(TriggerMsg)) return;

  TriggerMsg t;
  memcpy(&t, incomingData, sizeof(t));
  if (t.sensor < SENSORS) pendingSensor = (int8_t)t.sensor;
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
  return (cm >= MIN_VALID_CM && cm <= MAX_VALID_CM) ? cm : NAN;
}

void setup() {
  Serial.begin(115200);
  delay(100);

  for (int i = 0; i < SENSORS; i++) {
    pinMode(TRIG_PINS[i], OUTPUT);
    pinMode(ECHO_PINS[i], INPUT);
  }

  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  esp_wifi_set_promiscuous(true);
  esp_wifi_set_channel(WIFI_CHANNEL, WIFI_SECOND_CHAN_NONE);   // must match the centre's channel
  esp_wifi_set_promiscuous(false);

  Serial.print("This board's MAC (put it in leftMac or rightMac on the centre board): ");
  Serial.println(WiFi.macAddress());

  if (esp_now_init() != ESP_OK) {
    Serial.println("Error initialising ESP-NOW");
    return;
  }

  esp_now_peer_info_t peer = {};
  memcpy(peer.peer_addr, centralMac, 6);
  peer.channel = WIFI_CHANNEL;
  peer.ifidx = WIFI_IF_STA;
  peer.encrypt = false;
  if (esp_now_add_peer(&peer) != ESP_OK) {
    Serial.println("Failed to add the centre board as a peer");
  }

  esp_now_register_recv_cb(onTrigger);
}

void loop() {
  int8_t s = pendingSensor;
  if (s < 0) return;
  pendingSensor = -1;

  ResultMsg r;
  r.sensor = (uint8_t)s;
  r.dist = measureDistance(TRIG_PINS[s], ECHO_PINS[s]);
  esp_now_send(centralMac, (uint8_t *)&r, sizeof(r));
}
