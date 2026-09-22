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

float S11 = 0;
float S12 = 0;
float S13 = 0;
float S21 = 0;
float S22 = 0;
float S23 = 0;

float Sensor1;
float Sensor2;

float x;
float y;
float theta;

int baseline = 100; // distance between sensors

typedef struct StructMessage {
  int distance;
} StructMessage;

StructMessage message;

uint8_t transmitterMac[] = {0xE0, 0x5A, 0x1B, 0x1F, 0xD9, 0x20}; 

void dataRecv(const uint8_t *mac_addr, const uint8_t *incomingData, int len) {
  // if (len != sizeof(StructMessage)) {
  //   Serial.printf("Ignored packet: wrong size (%d bytes)\n", len);
  //   return;
  // }
  if (memcmp(mac_addr, transmitterMac, 6) != 0) {
    //Serial.println("Ignored packet: unknown sender");
    return;
  }
  memcpy(&message, incomingData, sizeof(message));
  Sensor2 = message.distance;
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

void updateSensors() {
   S11 = measureDistance(trigPin, echoPin);
   //S12 = measureDistance();
   //S13 = measureDistance();
   //S21 = measureDistance(trigPin2, echoPin2);
   //S22 = measureDistance();
   //S23 = measureDistance();
   //Serial.print("S11: "); Serial.print(S11);
   //Serial.print("  S21: "); Serial.println(S21);
}




void getLoc() {
  updateSensors();

  

  if (S11>0 && S12==0 && S13==0){
    Sensor1 = S11;
  } else if (S12>0 && S11==0 && S13==0){
      Sensor1 = S12;
  } else if (S13>0 && S11==0 && S12==0){
      Sensor1 = S13;
  } else if (S11>0 && S12>0 && S13>0){
      Sensor1 = S11;
  }

  // if (S21>0 && S22==0 && S23==0){
  //     Sensor2 = S21;
  // } else if (S22>0 && S21==0 && S23==0){
  //     Sensor2 = S22;
  // } else if (S23>0 && S21==0 && S22==0){
  //     Sensor2 = S23;
  // } else if (S21>0 && S22>0 && S23>0){
  //     Sensor2 = S21;
  // }

  if (Sensor1 > 0) {
    theta=acos((((Sensor1*Sensor1)+(baseline*baseline)-(Sensor2*Sensor2)))/(2*Sensor1*baseline));
  if(theta<3 && theta>0){               
    x=Sensor1*cos(theta)+ baseline/2; 
    y=Sensor1*sin(theta); 
  } } else {
    x = -1;
    y = -1;
  }
}

void setup(){
  Serial.begin(115200);
  if(!LittleFS.begin(true)) {
    Serial.println("LittleFS mount failed");
  }
  delay(100);

  pinMode(trigPin, OUTPUT);
  pinMode(echoPin, INPUT);

  WiFi.mode(WIFI_AP_STA);
  
  WiFi.softAP(ssid, password, WIFI_CHANNEL);

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

  Serial.println();
  Serial.print("x: ");
  Serial.print(x);
  Serial.print(" y: ");
  Serial.print(y);

  delay(100);
}



