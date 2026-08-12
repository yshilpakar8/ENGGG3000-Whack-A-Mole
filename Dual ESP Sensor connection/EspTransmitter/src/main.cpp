#include <Arduino.h>
#include <esp_now.h>
#include <WiFi.h>
#include "esp_wifi.h"

uint8_t broadcastAddress[] = {0xE0, 0x5A, 0x1B, 0x1F, 0xD9, 0x20};

const int WIFI_CHANNEL = 6;

int trigPin = 18;
int echoPin = 19;

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
  long duration = pulseIn(echoPin, HIGH);
  return duration * 0.0343 / 2;
}

void setup() {
  Serial.begin(115200);
  WiFi.mode(WIFI_STA);

  esp_wifi_set_channel(WIFI_CHANNEL, WIFI_SECOND_CHAN_NONE);

  pinMode(trigPin, OUTPUT);
  pinMode(echoPin, INPUT);

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

  message.distance = measureDistance(trigPin, echoPin);

  esp_err_t outcome = esp_now_send(broadcastAddress, (uint8_t *) &message, sizeof(message));

  if(outcome == ESP_OK) {
    Serial.println("Message sent successfully");
  } else {
    Serial.println("Error sending the message");
  }

  // Prints the distance on the Serial Monitor
  Serial.println("");
  Serial.print("Distance: ");
  Serial.println(message.distance);
  delay(10);

}
