#include <Arduino.h>
#include <esp_now.h>
#include <WiFi.h>
#include "esp_wifi.h"
#include <Servo.h>

//  trans mac = {0xE0, 0x5A, 0x1B, 0x1F, 0xD9, 0x20}; 

uint8_t broadcastAddress[] = {0x00, 0x70, 0x07, 0x7C, 0x8B, 0x04};

const int WIFI_CHANNEL = 6;

int trigPin1 = 18;
int echoPin1 = 19;


int baseline = 13;


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

typedef struct StructMessage {
  int distance;
} StructMessage;

StructMessage message;

void dataSent(const uint8_t *mac_addr, esp_now_send_status_t status) {
  Serial.print("\r\nStatus of Last message Sent:\t");
  Serial.println(status == ESP_NOW_SEND_SUCCESS ? "Delivery Success" : "Delivery Fail");
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
   S11 = measureDistance(trigPin1, echoPin1);
   //S12 = measureDistance();
   //S13 = measureDistance();
   //S21 = measureDistance(trigPin2, echoPin2);
   //S22 = measureDistance();
   //S23 = measureDistance();
   Serial.print("S11: "); Serial.print(S11);
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

}

void setup() {
  Serial.begin(115200);
  WiFi.mode(WIFI_STA);

  esp_wifi_set_channel(WIFI_CHANNEL, WIFI_SECOND_CHAN_NONE);

  pinMode(trigPin1, OUTPUT);
  pinMode(echoPin1, INPUT);
  //pinMode(trigPin2, OUTPUT);
  //pinMode(echoPin2, INPUT);

  if(esp_now_init() != ESP_OK) {
    Serial.println("Error initialising ESP-NOW");
    return;
  }

  esp_now_register_send_cb(dataSent);

  esp_now_peer_info_t peerInfo;

  memset(&peerInfo, 0, sizeof(peerInfo));

  memcpy(peerInfo.peer_addr, broadcastAddress, 6);
  peerInfo.channel = WIFI_CHANNEL;
  peerInfo.encrypt = false;

  if(esp_now_add_peer(&peerInfo) != ESP_OK) {
    Serial.println("Failed to add peer");
    return;
  }
}

void loop() {
  Serial.println(WiFi.macAddress());
  getLoc();

  message.distance = Sensor1;
  // Serial.println("x: ");
  // Serial.print(x);
  // Serial.print(" y: ");
  // Serial.print(y);

  esp_err_t outcome = esp_now_send(broadcastAddress, (uint8_t *) &message, sizeof(message));

  if(outcome == ESP_OK) {
    Serial.println("Message sent successfully");
  } else {
    Serial.println("Error sending the message");
  }
  delay(10);
}
