#include "polaris/deck_bitrate_advice.h"
#include <algorithm>
#include <cmath>
#include <limits>
namespace nova::deck::polaris {
namespace {
std::optional<int> number(const QJsonValue& v, int min = 0, int max = std::numeric_limits<int>::max()) {
    const auto n = v.toDouble(-1);
    if (!v.isDouble() || !std::isfinite(n) || std::floor(n) != n || n < min || n > max) return {};
    return static_cast<int>(n);
}
// Calibrated 31 dB at H 2.87 and 35 dB at H 2.0, 420 then 444.
// PyroWave 186f0393; header sha256 384e2ca2901318dd0e82a4bad44c7be902984382e87127d58c751b5ed95246a3.
// Exact literals also used by Android PyroWaveRateTable; the independent C fixture tests both ports.
constexpr double coefficients[4][8] = {
    {228.2718101713072, 71.62555853721143, -10.197601487728605, 80.77042821834353, 49.403545752716866, -243.24963287970016, -17.84085511844943, 162.90256838507636},
    {238.362964779069, 58.93750910298204, -10.779144089138825, 142.97949176542957, 63.39753948187021, -409.3257378090642, -15.903025270508008, 274.5564145601787},
    {611.9945665176643, 87.22375978784984, -374.2744854882103, 138.525763156332, 404.3879014230974, -64.04327593791751, -194.8035057145124, -12.904253458542499},
    {719.659360640365, 51.9223145866537, -522.3229212980714, 104.10383129754959, 605.6153091742109, 87.26139899802612, -306.13390524230874, -110.39755862218644}
};
}
int manualBitrateMaximum(const QJsonValue& v) { return std::min(500000, number(v,1000).value_or(300000)); }
int encoderForRequest(int request, int audio, int fec) {
    if (request <= 0 || audio < 0 || fec < 0 || fec > 255) return 0;
    const float split = fec <= 80 ? static_cast<float>(request) / (100.0f / (100 - fec)) : static_cast<float>(request);
    int rate = static_cast<int>(std::min(static_cast<double>(split),static_cast<double>(std::numeric_limits<int>::max())));
    rate -= std::min(audio, rate / 5); rate -= std::min(500, rate / 10); return rate;
}
int requestForEncoder(int encoder, int audio, int fec) {
    if (encoder <= 0 || audio < 0 || fec < 0 || fec > 255) return 0;
    int low = encoder-1, high = encoder;
    while (encoderForRequest(high,audio,fec) < encoder) {
        if (high > std::numeric_limits<int>::max()/2) { high=std::numeric_limits<int>::max(); break; }
        high *= 2;
    }
    while (high-low > 1) { const int mid=low+(high-low)/2; if (encoderForRequest(mid,audio,fec)>=encoder) high=mid; else low=mid; }
    return high;
}
int DeckBitrateUnits::requestFor(int value) const {
    if (split && encoder == value) return *split;
    const auto raw=requestForEncoder(value,audio,fec), grid=static_cast<int>(std::round(raw/500.0)*500);
    return std::abs(raw-grid)<=1 ? grid : raw;
}
std::optional<DeckBitrateUnits> parseBitrateUnits(const QJsonObject& o) {
    if (number(o.value("version")) != 1 || o.value("formula") != "stream_bitrate_v1") return {};
    DeckBitrateUnits v;
    for (const auto* key : {"split_kbps","cap_kbps","cap_source"}) if (!o.contains(key)) return {};
    auto request=number(o.value("requested_kbps")), encoder=number(o.value("encoder_kbps")), live=number(o.value("live_encoder_kbps"));
    auto audio=number(o.value("audio_kbps"),0,100000), fec=number(o.value("fec_percentage"),0,255), warp=number(o.value("warp_factor"),1);
    if (!request || !encoder || !live || !audio || !fec || !warp) return {};
    if (!o.value("split_kbps").isNull()) { v.split=number(o.value("split_kbps"),1); if (!v.split) return {}; }
    if (!o.value("cap_kbps").isNull()) { v.cap=number(o.value("cap_kbps"),1); if (!v.cap) return {}; }
    if (!o.value("cap_source").isNull()) { if (!o.value("cap_source").isString()) return {}; v.capSource=o.value("cap_source").toString(); if (v.capSource.trimmed().isEmpty() || v.capSource.size()>128) return {}; }
    if (v.cap.has_value() != !v.capSource.isEmpty()) return {};
    v.requested=*request;v.encoder=*encoder;v.liveEncoder=*live;v.audio=*audio;v.fec=*fec;v.warp=*warp;return v;
}
std::optional<DeckPyrowaveAdvice> parsePyrowaveAdvice(const QJsonObject& o) {
    if (number(o.value("version")) != 1 || (o.contains("available") && o.value("available") != true)) return {};
    const auto w=number(o.value("width"),1,16384), h=number(o.value("height"),1,16384), fps=number(o.value("fps"),1,1000);
    const auto goal=number(o.value("raise_goal_kbps"),1,500000), cap=number(o.value("cap_kbps"),1,500000);
    const auto limited=o.value("raise_goal_limited_by").toString();
    if (!w || !h || !fps || !goal || !cap || !QStringList{"advice","cap","max_bitrate"}.contains(limited)) return {};
    const auto assumes=o.value("assumes").toObject();
    const auto audio=assumes.contains("audio_kbps") ? number(assumes.value("audio_kbps"),0,100000) : std::optional<int>(512);
    const auto fec=assumes.contains("fec_percentage") ? number(assumes.value("fec_percentage"),0,255) : std::optional<int>(10);
    if (!audio || !fec) return {};
    return DeckPyrowaveAdvice{*w,*h,*fps,std::min({*goal,*cap,automaticBitrateMaximum}),*cap,*audio,*fec,limited,
        assumes.contains("audio_kbps") && assumes.contains("fec_percentage"), limited == "max_bitrate" ? goal : std::nullopt};
}
QVariantMap DeckPyrowaveAdvice::toMap() const {
    return {{"version",1},{"width",width},{"height",height},{"fps",fps},{"raise_goal_kbps",goal},{"cap_kbps",cap},
        {"raise_goal_limited_by",limitedBy},{"assumes",QVariantMap{{"audio_kbps",audio},{"fec_percentage",fec}}}};
}
int pyrowaveEncoderAdvice(int width,int height,int fps,bool room,bool chroma444) {
    if (width<=0 || height<=0 || fps<=0 || fps>1000) return 0;
    const double pixels=double(width)*height, edge=std::clamp(pixels,921600.0,8294400.0);
    const double x=std::sqrt(edge*1e-6)-2.0;
    double power=1,kbytes=0; for (const auto c:coefficients[(room?2:0)+(chroma444?1:0)]) { kbytes+=power*c;power*=x; }
    const double kbps=kbytes*8e-3*fps*1000*(pixels/edge);
    return std::isfinite(kbps) && kbps>0 ? static_cast<int>(std::min(kbps,double(std::numeric_limits<int>::max()))) : 0;
}
QVariantMap bitrateAdvice(int width,int height,int fps,const QJsonObject& host,bool room,bool chroma444) {
    const auto advice=parsePyrowaveAdvice(host);
    if (advice && advice->matches(width,height,fps)) return {{"kbps",advice->goal},{"basis","host"}};
    const int encoder=pyrowaveEncoderAdvice(width,height,fps,room,chroma444);
    return {{"kbps",encoder>0 ? std::min(automaticBitrateMaximum,requestForEncoder(encoder)) : 0},{"basis","calibrated"}};
}
}
