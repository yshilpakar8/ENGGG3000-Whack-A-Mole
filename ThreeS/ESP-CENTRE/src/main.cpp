/*#include <Arduino.h>
#include <WiFi.h>
#include <esp_now.h>
#include "esp_wifi.h"

const char* ssid = "ESP32_G34";
const char* password = "123456789";
const int WIFI_CHANNEL = 6;

uint8_t leftMac[]  = {0xE0, 0x5A, 0x1B, 0x1F, 0xD9, 0x20};
uint8_t rightMac[] = {0x00, 0x70, 0x07, 0x7C, 0x8B, 0x04}; 

WiFiServer Server(80);
WiFiClient client;

// ----------------------------- Geometry -------------------------------------
// X position (cm) of every sensor along the front line. MEASURE THESE.
//   Index order: left ESP s0, s1 | centre ESP s0, s1 | right ESP s0, s1
const float BASELINE_CM = 150.0f;                 // distance left corner ESP to right corner ESP
const float CENTRE_X    = BASELINE_CM / 2.0f;     // centre ESP mounting position
const float SENSOR_PITCH_CM = 10.0f;              // spacing between the 2 sensors on one board

const int NUM_UNITS = 3;                          // 0 = left, 1 = centre, 2 = right
const int SENSORS_PER_UNIT = 2;
const int TOTAL_SENSORS = NUM_UNITS * SENSORS_PER_UNIT;

const float SENSOR_X[TOTAL_SENSORS] = {
  0.0f,                           0.0f + SENSOR_PITCH_CM,                  // left unit
  CENTRE_X - SENSOR_PITCH_CM / 2, CENTRE_X + SENSOR_PITCH_CM / 2,          // centre unit
  BASELINE_CM - SENSOR_PITCH_CM,  BASELINE_CM                              // right unit
};

const int UNIT_OF[TOTAL_SENSORS] = {0, 0, 1, 1, 2, 2};

// local sensors
const int TRIG_PINS[SENSORS_PER_UNIT] = {18, 12};
const int ECHO_PINS[SENSORS_PER_UNIT] = {19, 14};

const float MIN_VALID_CM = 3.0f;
const float MAX_VALID_CM = 250.0f;
const unsigned long ECHO_TIMEOUT_US = 15000;
const unsigned long SENSOR_GAP_MS = 5;

const uint8_t FILTER_WINDOW = 5;
const uint8_t FILTER_MIN_VALID = 3;


const unsigned long REMOTE_TIMEOUT_MS = 300;   // remote data older than this is ignored
const unsigned long HOLD_MS = 300;             // keep last position this long after losing it
const float CLUSTER_CM = 15.0f;                // measurments within this of the best one are averaged
const float MAX_JUMP_CM = 60.0f;               // bigger jumps are treated as outliers and are ignored
const uint8_t RELOCK_CYCLES = 3;               // pos only recalculated afger this many updates

// math stuff zzzzz
const float ALPHA_MIN = 0.25f;
const float ALPHA_GAIN = 0.02f;
const float ALPHA_MAX = 0.9f;

const bool DEBUG_PRINT = true;
const unsigned long DEBUG_INTERVAL_MS = 250;

// ESP-NOW payload
typedef struct StructMessage {
  float dist[SENSORS_PER_UNIT];
} StructMessage;

portMUX_TYPE remoteMux = portMUX_INITIALIZER_UNLOCKED;
float remoteDist[2][SENSORS_PER_UNIT] = {{-1.0f, -1.0f}, {-1.0f, -1.0f}};   // [0]=left, [1]=right
unsigned long lastRemoteUpdateMs[2] = {0, 0};

void dataRecv(const uint8_t *mac_addr, const uint8_t *incomingData, int len) {
  int slot;
  if (memcmp(mac_addr, leftMac, 6) == 0) slot = 0;
  else if (memcmp(mac_addr, rightMac, 6) == 0) slot = 1;
  else return;
  if (len != (int)sizeof(StructMessage)) return;

  StructMessage m;
  memcpy(&m, incomingData, sizeof(m));

  portENTER_CRITICAL(&remoteMux);
  memcpy(remoteDist[slot], m.dist, sizeof(m.dist));
  lastRemoteUpdateMs[slot] = millis();
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

SensorFilter localFilter[SENSORS_PER_UNIT];
float localDist[SENSORS_PER_UNIT];

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
  for (int i = 0; i < SENSORS_PER_UNIT; i++) {
    localDist[i] = localFilter[i].update(measureDistance(TRIG_PINS[i], ECHO_PINS[i]));
    if (i < SENSORS_PER_UNIT - 1) delay(SENSOR_GAP_MS);
  }
}

// ----------------------------- Trilateration ---------------------------------
// Intersection of two circles centred at (p1x,0) and (p2x,0) with radii d1, d2.
// Returns the intersection with y >= 0 (in front of the sensor line).
bool trilaterate(float d1, float d2, float p1x, float p2x, float &outX, float &outY) {
  float D = p2x - p1x;
  if (fabsf(D) < 1.0f) return false;

  float xl = (d1 * d1 - d2 * d2 + D * D) / (2.0f * D);
  float ySq = d1 * d1 - xl * xl;
  if (ySq < -25.0f) return false;   // circles don't meet (allow a little measurement noise)
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
int candCount = 0;   

void getLoc() {
  updateSensors();


  float rd[2][SENSORS_PER_UNIT];
  unsigned long remoteAge[2];
  unsigned long now = millis();
  portENTER_CRITICAL(&remoteMux);
  memcpy(rd, remoteDist, sizeof(rd));
  remoteAge[0] = now - lastRemoteUpdateMs[0];
  remoteAge[1] = now - lastRemoteUpdateMs[1];
  portEXIT_CRITICAL(&remoteMux);

  // Assemble all six distances (NAN = unavailable)
  float d[TOTAL_SENSORS];
  for (int s = 0; s < SENSORS_PER_UNIT; s++) {
    d[0 * SENSORS_PER_UNIT + s] = (remoteAge[0] <= REMOTE_TIMEOUT_MS) ? rd[0][s] : NAN;  // left
    d[1 * SENSORS_PER_UNIT + s] = localDist[s];                                          // centre
    d[2 * SENSORS_PER_UNIT + s] = (remoteAge[1] <= REMOTE_TIMEOUT_MS) ? rd[1][s] : NAN;  // right
  }

  struct Cand { float x, y; };
  Cand c[TOTAL_SENSORS * TOTAL_SENSORS];
  int n = 0;

  for (int i = 0; i < TOTAL_SENSORS; i++) {
    if (!validDist(d[i])) continue;
    for (int j = i + 1; j < TOTAL_SENSORS; j++) {
      if (UNIT_OF[i] == UNIT_OF[j]) continue;
      if (!validDist(d[j])) continue;
      float cx, cy;
      if (trilaterate(d[i], d[j], SENSOR_X[i], SENSOR_X[j], cx, cy)) {
        c[n++] = {cx, cy};
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
      float dd = hypotf(c[k].x - rx, c[k].y - ry);
      if (dd < bestD) { bestD = dd; best = k; }
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

  // Smooth / outlier rejector
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

  for (int i = 0; i < SENSORS_PER_UNIT; i++) {
    pinMode(TRIG_PINS[i], OUTPUT);
    pinMode(ECHO_PINS[i], INPUT);
    localDist[i] = NAN;
  }

  WiFi.mode(WIFI_AP_STA);
  WiFi.softAP(ssid, password, WIFI_CHANNEL);
  Serial.print("AP IP address: ");
  Serial.println(WiFi.softAPIP());
  Serial.print("AP MAC (use as receiverMac in the transmitters): ");
  Serial.println(WiFi.softAPmacAddress());

  if (esp_now_init() != ESP_OK) {
    Serial.println("Error initialising ESP-NOW Link");
    return;
  }
  esp_now_register_recv_cb(dataRecv);

  Server.begin();
}

void loop() {
  //Serial.println(WiFi.softAPmacAddress());
  getLoc();
  serviceClient();

  if (client && client.connected()) {
    client.printf("x: %.1f y: %.1f\n", x, y);
  }

  if (DEBUG_PRINT) {
    static unsigned long lastPrint = 0;
    if (millis() - lastPrint >= DEBUG_INTERVAL_MS) {
      lastPrint = millis();
      Serial.printf("L[%.0f %.0f] C[%.0f %.0f] R[%.0f %.0f]  cands=%d  x=%.1f y=%.1f\n",
                    remoteDist[0][0], remoteDist[0][1],
                    localDist[0], localDist[1],
                    remoteDist[1][0], remoteDist[1][1],
                    candCount, x, y);
    }
  }
}
*/

#include <Arduino.h>
#include <WiFi.h>
#include <esp_now.h>
#include "esp_wifi.h"


// =====================================================
// SELECT ESP32 ROLE BEFORE UPLOAD
// =====================================================

// Uncomment ONE only

//#define ROLE_LEFT
//#define ROLE_RIGHT
#define ROLE_CENTRE



// =====================================================
// WIFI / ESP-NOW SETTINGS
// =====================================================

const int WIFI_CHANNEL = 6;


// =====================================================
// MAC ADDRESSES
// =====================================================

// Centre ESP32 MAC
uint8_t centreMac[] = {
  0x00, 0x70, 0x07, 0x7C, 0x8B, 0x05
};


// Left ESP32 MAC
uint8_t leftMac[] = {
  0xE0, 0x5A, 0x1B, 0x1F, 0xD9, 0x20
};


// Right ESP32 MAC
uint8_t rightMac[] = {
  0x00, 0x70, 0x07, 0x7C, 0x8B, 0x04
};



// =====================================================
// SENSOR CONFIGURATION
// =====================================================

const int SENSORS_PER_UNIT = 2;


// Local sensor pins
const int TRIG_PINS[SENSORS_PER_UNIT] = {
  18,
  12
};

const int ECHO_PINS[SENSORS_PER_UNIT] = {
  19,
  14
};



const float MIN_VALID_CM = 3.0f;
const float MAX_VALID_CM = 250.0f;

const unsigned long ECHO_TIMEOUT_US = 15000;



// =====================================================
// FILTER SETTINGS
// =====================================================

const uint8_t FILTER_WINDOW = 5;
const uint8_t FILTER_MIN_VALID = 3;



// =====================================================
// ESP-NOW MESSAGE STRUCTURES
// =====================================================


// Centre tells a remote ESP32:
// "measure this sensor"

enum CommandType {
  MEASURE_COMMAND = 1
};


typedef struct {

  uint8_t command;

  // 0 or 1
  uint8_t sensorID;

  // used to match measurements
  uint32_t sequence;

} TriggerMessage;




// Sensor node replies:

typedef struct {

  uint8_t sensorID;

  float distance;

  uint32_t sequence;

} DistanceMessage;





// =====================================================
// MEDIAN FILTER
// =====================================================


struct SensorFilter {

  float hist[FILTER_WINDOW];

  uint8_t idx = 0;


  SensorFilter()
  {
    for(uint8_t i=0;i<FILTER_WINDOW;i++)
    {
      hist[i]=NAN;
    }
  }



  float update(float value)
  {

    hist[idx]=value;

    idx++;

    if(idx>=FILTER_WINDOW)
      idx=0;



    float temp[FILTER_WINDOW];

    uint8_t count=0;


    for(uint8_t i=0;i<FILTER_WINDOW;i++)
    {
      if(!isnan(hist[i]))
      {
        temp[count++]=hist[i];
      }
    }


    if(count < FILTER_MIN_VALID)
      return NAN;



    // sort
    for(uint8_t i=1;i<count;i++)
    {
      float key=temp[i];

      int8_t j=i-1;


      while(j>=0 && temp[j]>key)
      {
        temp[j+1]=temp[j];
        j--;
      }


      temp[j+1]=key;
    }


    return temp[count/2];
  }

};



SensorFilter localFilter[SENSORS_PER_UNIT];

float localDist[SENSORS_PER_UNIT];



bool validDist(float d)
{
  return !isnan(d) &&
         d>=MIN_VALID_CM &&
         d<=MAX_VALID_CM;
}
// =====================================================
// ULTRASONIC MEASUREMENT
// =====================================================

float measureDistance(int trigPin, int echoPin)
{

  digitalWrite(trigPin, LOW);
  delayMicroseconds(3);


  digitalWrite(trigPin, HIGH);
  delayMicroseconds(10);
  digitalWrite(trigPin, LOW);



  unsigned long duration =
    pulseIn(
      echoPin,
      HIGH,
      ECHO_TIMEOUT_US
    );



  if(duration == 0)
    return NAN;



  float cm =
    duration * 0.0343f / 2.0f;



  return validDist(cm) ? cm : NAN;
}





// =====================================================
// ESP-NOW PEER ADD FUNCTION
// =====================================================

void addPeer(uint8_t *mac)
{

  esp_now_peer_info_t peerInfo = {};

  memcpy(
    peerInfo.peer_addr,
    mac,
    6
  );


  peerInfo.channel = WIFI_CHANNEL;

  peerInfo.encrypt = false;



  if(esp_now_add_peer(&peerInfo) != ESP_OK)
  {
    Serial.println("Failed adding peer");
  }

}





// =====================================================
// SEND DISTANCE BACK TO CENTRE
// =====================================================

void sendDistance(
  uint8_t sensorID,
  float distance,
  uint32_t sequence
)
{

  DistanceMessage msg;


  msg.sensorID = sensorID;

  msg.distance = distance;

  msg.sequence = sequence;



  esp_now_send(
    centreMac,
    (uint8_t*)&msg,
    sizeof(msg)
  );

}





// =====================================================
// LEFT / RIGHT NODE RECEIVE COMMAND
// =====================================================

void nodeReceive(
  const uint8_t *mac,
  const uint8_t *incomingData,
  int len
)
{

  if(len != sizeof(TriggerMessage))
    return;



  TriggerMessage command;


  memcpy(
    &command,
    incomingData,
    sizeof(command)
  );



  if(command.command != MEASURE_COMMAND)
    return;



  if(command.sensorID >= SENSORS_PER_UNIT)
    return;



  Serial.print("Measuring sensor ");
  Serial.println(command.sensorID);



  float distance =
    measureDistance(
      TRIG_PINS[command.sensorID],
      ECHO_PINS[command.sensorID]
    );



  float filtered =
    localFilter[command.sensorID].update(distance);



  sendDistance(
    command.sensorID,
    filtered,
    command.sequence
  );

}





// =====================================================
// LEFT / RIGHT NODE SETUP
// =====================================================

void setupSensorNode()
{

  Serial.begin(115200);



  for(int i=0;i<SENSORS_PER_UNIT;i++)
  {

    pinMode(
      TRIG_PINS[i],
      OUTPUT
    );


    pinMode(
      ECHO_PINS[i],
      INPUT
    );


    localDist[i]=NAN;
  }





  WiFi.mode(WIFI_STA);



  esp_wifi_set_promiscuous(true);


  esp_wifi_set_channel(
    WIFI_CHANNEL,
    WIFI_SECOND_CHAN_NONE
  );


  esp_wifi_set_promiscuous(false);





  if(esp_now_init()!=ESP_OK)
  {

    Serial.println(
      "ESP-NOW init failed"
    );

    return;
  }





  esp_now_register_recv_cb(
    nodeReceive
  );



  addPeer(
    centreMac
  );



  Serial.println(
    "SENSOR NODE READY"
  );

}





void loopSensorNode()
{

  // Nothing here.
  // The node only measures when commanded
  delay(10);

}

// =====================================================
// CENTRE MASTER DATA STORAGE
// =====================================================


float remoteDist[2][SENSORS_PER_UNIT] =
{
  {-1,-1},
  {-1,-1}
};


// 0 = left
// 1 = right

unsigned long lastRemoteUpdate[2] =
{
  0,
  0
};



// Centre local measurements
float centreDist[SENSORS_PER_UNIT];



// =====================================================
// SENSOR ID MAP
// =====================================================


// Global sensor numbering:
//
// 0 = Left S0
// 1 = Left S1
// 2 = Centre S0
// 3 = Centre S1
// 4 = Right S0
// 5 = Right S1


enum GlobalSensor
{

  LEFT_S0 = 0,
  LEFT_S1 = 1,

  CENTRE_S0 = 2,
  CENTRE_S1 = 3,

  RIGHT_S0 = 4,
  RIGHT_S1 = 5

};





// =====================================================
// MASTER MEASUREMENT SCHEDULE
// =====================================================

const uint8_t SENSOR_SCHEDULE[6] =
{

  LEFT_S0,
  LEFT_S1,

  CENTRE_S0,
  CENTRE_S1,

  RIGHT_S0,
  RIGHT_S1

};



uint32_t sequenceNumber = 0;





// =====================================================
// SEND MEASUREMENT COMMAND
// =====================================================

void sendMeasurementCommand(
  uint8_t sensorID
)
{

  TriggerMessage msg;


  msg.command =
    MEASURE_COMMAND;


  msg.sensorID =
    sensorID % 2;


  msg.sequence =
    sequenceNumber;



  uint8_t *destination;



  if(sensorID <= LEFT_S1)
  {
    destination = leftMac;
  }

  else if(sensorID >= RIGHT_S0)
  {
    destination = rightMac;
  }

  else
  {
    // centre sensor
    return;
  }




  esp_now_send(
    destination,
    (uint8_t*)&msg,
    sizeof(msg)
  );

}







// =====================================================
// CENTRE SENSOR MEASUREMENT
// =====================================================

void measureCentreSensor(
  uint8_t sensorID
)
{

  uint8_t localID =
    sensorID - CENTRE_S0;



  float d =
    measureDistance(
      TRIG_PINS[localID],
      ECHO_PINS[localID]
    );



  centreDist[localID] =
    localFilter[localID].update(d);

}







// =====================================================
// RECEIVE DISTANCE FROM REMOTE ESP32
// =====================================================

void masterReceive(
 const uint8_t *mac,
 const uint8_t *incomingData,
 int len
)
{

  if(len != sizeof(DistanceMessage))
    return;



  DistanceMessage msg;


  memcpy(
    &msg,
    incomingData,
    sizeof(msg)
  );



  // From LEFT ESP32

  if(memcmp(mac,leftMac,6)==0)
  {

    remoteDist[0][msg.sensorID] =
      msg.distance;


    lastRemoteUpdate[0] =
      millis();

  }



  // From RIGHT ESP32

  else if(memcmp(mac,rightMac,6)==0)
  {

    remoteDist[1][msg.sensorID] =
      msg.distance;


    lastRemoteUpdate[1] =
      millis();

  }

}








// =====================================================
// RUN ONE COMPLETE MEASUREMENT CYCLE
// =====================================================

void runMeasurementCycle()
{


  sequenceNumber++;



  for(int i=0;i<6;i++)
  {


    uint8_t sensor =
      SENSOR_SCHEDULE[i];



    Serial.print(
      "Slot sensor "
    );


    Serial.println(sensor);





    if(sensor == CENTRE_S0 ||
       sensor == CENTRE_S1)
    {

      measureCentreSensor(
        sensor
      );

    }

    else
    {

      sendMeasurementCommand(
        sensor
      );



      // allow remote sensor time
      delay(25);

    }



    // ultrasonic quiet time
    delay(10);

  }



}

// =====================================================
// CENTRE SETUP
// =====================================================

void setupCentre()
{

  Serial.begin(115200);



  for(int i=0;i<SENSORS_PER_UNIT;i++)
  {

    pinMode(
      TRIG_PINS[i],
      OUTPUT
    );


    pinMode(
      ECHO_PINS[i],
      INPUT
    );


    centreDist[i]=NAN;

  }





  WiFi.mode(
    WIFI_AP_STA
  );



  esp_wifi_set_promiscuous(true);


  esp_wifi_set_channel(
    WIFI_CHANNEL,
    WIFI_SECOND_CHAN_NONE
  );


  esp_wifi_set_promiscuous(false);





  if(esp_now_init()!=ESP_OK)
  {

    Serial.println(
      "ESP-NOW failed"
    );

    return;

  }





  esp_now_register_recv_cb(
    masterReceive
  );



  addPeer(leftMac);

  addPeer(rightMac);



  Serial.println(
    "CENTRE MASTER READY"
  );

}






void loopCentre()
{

  runMeasurementCycle();


  delay(50);

}
// =====================================================
// TRILATERATION GEOMETRY
// =====================================================


// Sensor positions along X axis (cm)

const float BASELINE_CM = 150.0f;

const float CENTRE_X =
  BASELINE_CM / 2.0f;


const float SENSOR_PITCH_CM = 10.0f;



const int TOTAL_SENSORS = 6;



const float SENSOR_X[TOTAL_SENSORS] =
{

  0.0f,
  SENSOR_PITCH_CM,


  CENTRE_X - SENSOR_PITCH_CM/2,
  CENTRE_X + SENSOR_PITCH_CM/2,


  BASELINE_CM - SENSOR_PITCH_CM,
  BASELINE_CM

};



// Which ESP32 each sensor belongs to

const int UNIT_OF[TOTAL_SENSORS] =
{
  0,0,
  1,1,
  2,2
};





// =====================================================
// TWO CIRCLE TRILATERATION
// =====================================================


bool trilaterate(
 float d1,
 float d2,
 float p1x,
 float p2x,
 float &outX,
 float &outY
)
{

  float D =
    p2x-p1x;



  if(fabsf(D)<1.0f)
    return false;



  float xl =
    (d1*d1 -
     d2*d2 +
     D*D)
     /
     (2.0f*D);



  float ySq =
    d1*d1-xl*xl;



  if(ySq < -25)
    return false;



  if(ySq<0)
    ySq=0;



  outX =
    p1x+xl;


  outY =
    sqrtf(ySq);



  return true;

}






// =====================================================
// LOCATION SOLVER
// =====================================================


float posX=-1;
float posY=-1;



void calculateLocation()
{

  float d[TOTAL_SENSORS];



  // LEFT

  d[0]=remoteDist[0][0];
  d[1]=remoteDist[0][1];


  // CENTRE

  d[2]=centreDist[0];
  d[3]=centreDist[1];


  // RIGHT

  d[4]=remoteDist[1][0];
  d[5]=remoteDist[1][1];




  struct Candidate
  {
    float x;
    float y;
  };


  Candidate candidates[36];

  int count=0;



  for(int i=0;i<TOTAL_SENSORS;i++)
  {

    if(!validDist(d[i]))
      continue;



    for(int j=i+1;j<TOTAL_SENSORS;j++)
    {

      if(!validDist(d[j]))
        continue;



      if(UNIT_OF[i]==UNIT_OF[j])
        continue;



      float x,y;



      if(trilaterate(
        d[i],
        d[j],
        SENSOR_X[i],
        SENSOR_X[j],
        x,
        y))
      {

        candidates[count++] =
        {
          x,y
        };

      }

    }

  }




  if(count==0)
  {
    posX=-1;
    posY=-1;
    return;
  }





  // Average all valid candidates

  float sx=0;
  float sy=0;



  for(int i=0;i<count;i++)
  {
    sx += candidates[i].x;
    sy += candidates[i].y;
  }



  posX =
    sx/count;


  posY =
    sy/count;



}


// =====================================================
// JAVA TCP OUTPUT
// =====================================================


WiFiServer Server(80);

WiFiClient client;



void serviceClient()
{

  if(client &&
     client.connected())
     return;



  WiFiClient incoming =
    Server.available();



  if(incoming)
  {

    client=incoming;

    client.setNoDelay(true);

    Serial.println(
      "Java client connected"
    );

  }

}






// =====================================================
// FINAL SETUP
// =====================================================


void setup()
{

#if defined(ROLE_LEFT)


  setupSensorNode();



#elif defined(ROLE_RIGHT)


  setupSensorNode();



#elif defined(ROLE_CENTRE)


  setupCentre();



  WiFi.softAP(
    "ESP32_G34",
    "123456789",
    WIFI_CHANNEL
  );


  Server.begin();



#else

#error "Select a role"

#endif

}







// =====================================================
// FINAL LOOP
// =====================================================


void loop()
{

#if defined(ROLE_LEFT)


  loopSensorNode();



#elif defined(ROLE_RIGHT)


  loopSensorNode();



#elif defined(ROLE_CENTRE)



  runMeasurementCycle();


  calculateLocation();



  serviceClient();




  if(client &&
     client.connected())
  {

    client.printf(
      "x: %.1f y: %.1f\n",
      posX,
      posY
    );

  }



  Serial.printf(
    "Position X %.1f Y %.1f\n",
    posX,
    posY
  );



  delay(100);



#endif

}