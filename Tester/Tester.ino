// C++ code
//
/*
  Sweep

  by BARRAGAN <http://barraganstudio.com>
  This example code is in the public domain.

  modified 8 Nov 2013  by Scott Fitzgerald
  http://www.arduino.cc/en/Tutorial/Sweep
*/

//#include <Servo.h>

int sensor1CM = 0;
int sensor2CM = 0;


int pos = 0;


long readUltrasonicDistance(int triggerPin, int echoPin)
{
  pinMode(triggerPin, OUTPUT);  // Clear the trigger
  digitalWrite(triggerPin, LOW);
  delayMicroseconds(2);
  // Sets the trigger pin to HIGH state for 10 microseconds
  digitalWrite(triggerPin, HIGH);
  delayMicroseconds(10);
  digitalWrite(triggerPin, LOW);
  pinMode(echoPin, INPUT);
  // Reads the echo pin, and returns the sound wave travel time in microseconds
  return pulseIn(echoPin, HIGH);
}

void setup(){
  Serial.begin(115200);
}

void loop(){
  //Sensor 1
  sensor1CM = readUltrasonicDistance(18, 19) * 0.0343 / 2;
  Serial.print("Sens 1:");
  Serial.print(sensor1CM);
  Serial.println("cm");
  //Sensor 
  
}