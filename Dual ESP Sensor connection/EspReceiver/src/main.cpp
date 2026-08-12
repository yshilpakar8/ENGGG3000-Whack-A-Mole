#include <Arduino.h>
#include <WiFi.h>
#include <esp_now.h>
#include <LittleFS.h>



// Wifi Variables
const char* ssid = "ESP32_G34";
const char* password = "123456789";

// recv mac = E0:5A:1B:1F:D9:20

WiFiServer Server(80);

String header;

const int WIFI_CHANNEL = 6;


unsigned long currentTime = millis();
unsigned long prevTime = 0;
const long timeout = 2000;

int webState = 0;

int distance1 = 0;
int remoteDistance = 0;

int trigPin = 18;
int echoPin = 19;

typedef struct StructMessage {
  int distance;
} StructMessage;

StructMessage message;

void dataRecv(const uint8_t *mac_addr, const uint8_t *incomingData, int len) {
  memcpy(&message, incomingData, sizeof(message));
  
  Serial.println("Bytes received: ");
  Serial.println(len);

  remoteDistance = message.distance;

  Serial.println("Integer: ");
  Serial.println(message.distance);

  
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

void setup(){
  Serial.begin(115200);
  if(!LittleFS.begin(true)) {
    Serial.println("LittleFS mount failed");
  }
  delay(100);

  pinMode(trigPin, OUTPUT);
  pinMode(echoPin, INPUT);

    
  WiFi.mode(WIFI_AP_STA);

  //WiFi.begin();
  
  WiFi.softAP(ssid, password, WIFI_CHANNEL);

  if(esp_now_init() != ESP_OK) {
    Serial.println("Error initiailising ESP-NOW Link");
    return;
  }

  esp_now_register_recv_cb(dataRecv);
  Serial.print("Connecting to WiFi ..");

  Serial.println("IP Address");
  Serial.println(WiFi.softAPIP());

  Server.begin();

}

void loop(){

  distance1 = measureDistance(trigPin, echoPin);

  WiFiClient client = Server.available();   // Listen for incoming clients


  if (client) {                             // If a new client connects,
    currentTime = millis();
    prevTime = currentTime;
    Serial.println("New Client.");          // print a message out in the serial port
    String currentLine = "";                // make a String to hold incoming data from the client
    while (client.connected() && currentTime - prevTime <= timeout) {  // loop while the client's connected
      currentTime = millis();
      if (client.available()) {             // if there's bytes to read from the client,
        char c = client.read();             // read a byte, then
        Serial.write(c);                    // print it out the serial monitor
        header += c;
        if (c == '\n') {  
          // Serve JSON status for live updates
          if (header.indexOf("GET /status") >= 0) {
            client.println("HTTP/1.1 200 OK");
            client.println("Content-type: application/json");
            client.println("Cache-Control: no-cache");
            client.println("Connection: close");
            client.println();
            client.print("{\"distance1\":"); client.print(distance1); 
            client.print(",\"remoteDistance\":"); client.print(remoteDistance); 
            client.println("}");
            client.println("");
            break;
          }  

          File file = LittleFS.open("/index.html", "r");
          if (file) {
            client.println("HTTP/1.1 200 OK");
            client.println("Content-type:text/html");
            client.println("Connection: close");
            client.println();
            while (file.available()) {
              client.write(file.read());
            }
            file.close();
            break;
          }

          else { // if you got a newline, then clear currentLine
            currentLine = "";
          }
        } else if (c != '\r') {  // if you got anything else but a carriage return character,
          currentLine += c;      // add it to the end of the currentLine
        }
      }
    }
    // Clear the header variable
    header = "";
    // Close the connection  
    client.stop();
    Serial.println("Client disconnected.");
    Serial.println("");
  }

  // Prints the distance on the Serial Monitor
  Serial.println("");
  Serial.print("Distance: ");
  Serial.println(distance1);

  Serial.println("");
  Serial.print("Remote Dist: ");
  Serial.println(remoteDistance);
  delay(10);
  
}



