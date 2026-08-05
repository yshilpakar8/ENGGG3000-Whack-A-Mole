int sensor1CM = 0;
int sensor2CM = 0;


int trigSensL = 6;
int echoSensL = 7;

int echoSensR = 5;
int trigSensR = 4;

void setup(){
  Serial.begin(9600);
  pinMode(trigSensL, OUTPUT);
}

void loop(){
  //Sensor 1
  sensor1CM = measureDistance(6, 7);
  Serial.print("Sens L:");
  Serial.print(sensor1CM);
  Serial.println("cm");
  
  
  //Sensor 2
  sensor2CM = measureDistance(4, 5);
  Serial.print("Sens R:");
  Serial.print(sensor2CM);
  Serial.println("cm");
  
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
