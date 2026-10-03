#include "polaris/deck_bitrate_advice.h"
#include <QCoreApplication>
#include <QFile>
#include <QJsonObject>
#include <cstdlib>
#include <iostream>
using namespace nova::deck::polaris;
void require(bool b,const char* m) { if (!b) { std::cerr<<m<<'\n';std::exit(1); } }
int main(int argc,char** argv) {
 QCoreApplication app(argc,argv);
 QFile fixture(NOVA_DECK_RATE_FIXTURE);require(fixture.open(QIODevice::ReadOnly),"shared upstream fixture missing");
 int rows=0;
 while (!fixture.atEnd()) {
  const auto cells=fixture.readLine().trimmed().split(',');
  if (cells.size()!=7 || !((cells[0]=="31" && cells[1]=="15") || (cells[0]=="35" && cells[1]=="8"))) continue;
  const bool room=cells[0]=="35", chroma=cells[2]=="1";
  const int width=cells[3].toInt(),height=cells[4].toInt(),fps=cells[5].toInt(),expected=int(cells[6].toDouble()*1000);
  require(pyrowaveEncoderAdvice(width,height,fps,room,chroma)==expected,"Linux calibration differs from shared upstream C fixture");
  require(bitrateAdvice(width,height,fps,{},room,chroma).value("kbps").toInt()==std::min(300000,requestForEncoder(expected)),"fallback request units diverged");++rows;
 }
 require(rows==128,"calibrated fixture coverage changed");
 require(pyrowaveEncoderAdvice(640,360,60)==pyrowaveEncoderAdvice(1280,720,60)/4,"small size did not use calibrated edge");
 for (int encoder:{1000,20000,180000,270000}) for(int fec:{0,10,20,80,81}) {
  const int request=requestForEncoder(encoder,512,fec);
  require(encoderForRequest(request,512,fec)>=encoder && encoderForRequest(request-1,512,fec)<encoder,"gross up does not invert single precision host formula");
 }
 require(encoderForRequest(500000)==448987 && requestForEncoder(448987)==499999,"manual500Mbps single precision host split changed");
 QJsonObject host{{"version",1},{"available",true},{"width",1920},{"height",1080},{"fps",120},{"raise_goal_kbps",201125},{"cap_kbps",300000},{"raise_goal_limited_by","advice"}};
 require(bitrateAdvice(1920,1080,120,host).value("kbps").toInt()==201125,"host request advice grossed up twice");
 require(bitrateAdvice(1280,720,60,host).value("basis")=="calibrated","stale host size applied");
 host["raise_goal_kbps"]=400000;host["cap_kbps"]=400000;host["raise_goal_limited_by"]="max_bitrate";
 require(parsePyrowaveAdvice(host)->manualMaximum==400000 && parsePyrowaveAdvice(host)->goal==300000,"manual cap conflated with automatic cap");
 for(const QJsonValue bad:{QJsonValue(true),QJsonValue("400000"),QJsonValue(1.5),QJsonValue(0),QJsonValue(QJsonValue::Null)}) require(manualBitrateMaximum(bad)==300000,"malformed range escaped legacy bound");
 require(manualBitrateMaximum(500000)==500000 && manualBitrateMaximum(900000)==500000,"manual feature bound wrong");
 QJsonObject units{{"version",1},{"formula","stream_bitrate_v1"},{"requested_kbps",400000},{"encoder_kbps",178988},{"live_encoder_kbps",178988},{"audio_kbps",512},{"fec_percentage",10},{"warp_factor",2},{"split_kbps",200000},{"cap_kbps",QJsonValue::Null},{"cap_source",QJsonValue::Null}};
 require(parseBitrateUnits(units)->requestFor(178988)==200000,"original request substituted for negotiated split");
 units["split_kbps"]=QJsonValue::Null;require(parseBitrateUnits(units) && !parseBitrateUnits(units)->split,"null split became formula budget");
 units.remove("split_kbps");require(!parseBitrateUnits(units),"missing split accepted as null");
 return 0;
}
