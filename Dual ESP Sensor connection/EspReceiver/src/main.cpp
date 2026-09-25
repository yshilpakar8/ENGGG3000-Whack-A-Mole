#include <Arduino.h>
#include <WiFi.h>
#include <esp_now.h>
#include <LittleFS.h>
#include "esp_wifi.h"
#include <Servo.h>


// Wifi Variables
const char* ssid = "ESP32_G34";
const char* password = "123456789";

// recv mac {0x00, 0x70, 0x07, 0x7C, 0x8B, 0x04}; 

WiFiServer Server(80);
WiFiClient client;

String header;

const int WIFI_CHANNEL = 6;

unsigned long currentTime = millis();
unsigned long prevTime = 0;
const long timeout = 2000;

int webState = 0;

int missCount = 0;

int distance1 = 0;
int remoteDistance = 0;

int trigPin = 18;
int echoPin = 19;

float S1 = 0;
float S2 = 0;
float S3 = 0;


float localDist;
float transDist;

float x;
float y;
float theta;

float baseline = 150; // distance between sensors

typedef struct StructMessage {
  int distance;
} StructMessage;

StructMessage message;

uint8_t transmitterMac[] = {0xE0, 0x5A, 0x1B, 0x1F, 0xD9, 0x20}; 

void dataRecv(const uint8_t *mac_addr, const uint8_t *incomingData, int len) {
  if (memcmp(mac_addr, transmitterMac, 6) != 0) {
    return;
  }
  memcpy(&message, incomingData, sizeof(message));
  transDist = message.distance;
}


long measureDistance(int triggerPin, int echoPin)
{
  digitalWrite(triggerPin, LOW);
  delayMicroseconds(2);
  digitalWrite(triggerPin, HIGH);
  delayMicroseconds(10);
  digitalWrite(triggerPin, LOW);
    
  long duration = pulseIn(echoPin, HIGH, 20000);
  return duration * 0.0343 / 2;
}
const uint8_t SMOOTHING_SAMPLES = 4;
unsigned long lastSampleTime = 0;
float distHistoryA[SMOOTHING_SAMPLES] = {0};
float distHistoryB[SMOOTHING_SAMPLES] = {0};
uint8_t historyIndex = 0;
bool historyFilled = false;

float smooth(float history[], float newValue) {
  history[historyIndex] = newValue;

  float sum = 0.0f;
  uint8_t count = 0;
  for (uint8_t i = 0; i < SMOOTHING_SAMPLES; i++) {
    if (!isnan(history[i])) {
      sum += history[i];
      count++;
    }
  }
  return (count > 0) ? (sum / count) : NAN;
}

void updateSensors() {
   S1 = measureDistance(trigPin, echoPin);
   //S12 = measureDistance();
   //S13 = measureDistance();
   //S21 = measureDistance(trigPin2, echoPin2);
   //S22 = measureDistance();
   //S23 = measureDistance();
   //Serial.print("S11: "); Serial.print(S11);
   //Serial.print("  S21: "); Serial.println(S21);
}

bool trilaterate(float d1, float d2, float baseline, float &x, float &y) {
  if (isnan(d1) || isnan(d2)) return false;

  x = (d1 * d1 - d2 * d2 + baseline * baseline) / (2.0f * baseline);

  float ySquared = d1 * d1 - x * x;
  if (ySquared < 0.0f) {
    return false;
  }

  y = sqrt(ySquared);
  return true;
}


void getLoc() {
  updateSensors();

  if (S1>0 && S2==0 && S3==0){
    localDist = S1;
  } else if (S2>0 && S1==0 && S3==0){
      localDist = S2;
  } else if (S3>0 && S1==0 && S2==0){
      localDist = S3;
  } else if (S1>0 && S2>0 && S3>0){
      localDist = S1;
  }

  bool ok = trilaterate(localDist, transDist, baseline, x, y);
}

void serviceClient() {
  if (client && client.connected()) return;
  WiFiClient incoming = Server.available();
  if (incoming) {
    client = incoming;
    Serial.println("Java desktop app connected over WiFi.");
  }
}



void setup(){
  Serial.begin(115200);
  delay(100);

  pinMode(trigPin, OUTPUT);
  pinMode(echoPin, INPUT);

  WiFi.mode(WIFI_AP_STA);
  
  WiFi.softAP(ssid, password, WIFI_CHANNEL);
  Serial.print("AP IP address: ");
  Serial.println(WiFi.softAPIP());

  if(esp_now_init() != ESP_OK) {
    Serial.println("Error initiailising ESP-NOW Link");
    return;
  }

  esp_now_register_recv_cb(dataRecv);

  Server.begin();

}

void loop(){
  //Serial.println(WiFi.macAddress());
  getLoc();
  serviceClient();

  if(client && client.connected()) {
    client.print("x: "); client.print(x);
    client.print(" y: "); client.println(y);
  }

  // Serial.println();
  // Serial.print("x: ");
  // Serial.print(x);
  // Serial.print(" y: ");
  // Serial.print(y);

  delay(100);
}



