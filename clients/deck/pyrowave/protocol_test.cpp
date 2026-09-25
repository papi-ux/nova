#include "protocol.h"
#include "codec.h"
#include <cstdlib>
#include <iostream>
#include <string>
int main() {
    const auto require = [](bool value) { if (!value) std::abort(); };
    require(std::string(NOVA_PYROWAVE_PROFILE_TOKEN) == nova::pyrowave::bitstreamId);
    const std::string map = "a=rtpmap:99 PYROWAVE/90000";
    const std::string profile = "a=fmtp:99 " NOVA_PYROWAVE_PROFILE_TOKEN;
    require(nova_pyrowave_description_compatible((map + "\n" + profile + "\n").c_str()));
    require(nova_pyrowave_description_compatible((map + "\r\n" + profile + "\r\n").c_str()));
    require(nova_pyrowave_description_compatible((profile + "\n" + map).c_str()));
    require(nova_pyrowave_description_compatible("a=rtpmap:99\tPYROWAVE/90000 \t\r\na=fmtp:99  " NOVA_PYROWAVE_PROFILE_TOKEN "\t\n"));
    require(nova_pyrowave_description_compatible((map + "\n" + profile + "\na=rtpmap:990 H264/90000").c_str()));
    const std::string hdr = "pyrowave-186f0393-hdr2020pq420-v1";
    for (const auto& offers : {std::string(NOVA_PYROWAVE_PROFILE_TOKEN) + " " + hdr,
                              hdr + " " NOVA_PYROWAVE_PROFILE_TOKEN,
                              hdr + "\t" NOVA_PYROWAVE_PROFILE_TOKEN " \tother-profile"})
        require(nova_pyrowave_description_compatible((map + "\r\na=fmtp:99 " + offers + "\r\n").c_str()));
    require(!nova_pyrowave_description_compatible(nullptr));
    for (const auto& bad : {std::string{}, map, profile, map + "\n" + profile + "-other\n",
                           map + "\na=other:" NOVA_PYROWAVE_PROFILE_TOKEN "\n",
                           map + "\na=fmtp:98 " NOVA_PYROWAVE_PROFILE_TOKEN "\n",
                           map + "\n" + profile + "\n" + profile,
                           map + "\n" + profile + "\na=fmtp:99 incompatible",
                           map + "\n" + profile + "\na=rtpmap:99 H264/90000",
                           map + "\n" + profile + "\na=rtpmap:99\tH264/90000",
                           map + "\n" + profile + "\na=fmtp:99\tincompatible",
                           map + "\n" + profile + "\na=rtpmap:099 H264/90000",
                           map + "\n" + profile + "\na=rtpmap:99",
                           map + "\n" + profile + "\na=rtpmap:99PYROWAVE/90000",
                           map + "\na=fmtp:99 " + hdr,
                           map + "\na=fmtp:99 " NOVA_PYROWAVE_PROFILE_TOKEN "0 " + hdr,
                           map + "\na=fmtp:99 prefix-" NOVA_PYROWAVE_PROFILE_TOKEN " " + hdr,
                           map + "\na=fmtp:99 " NOVA_PYROWAVE_PROFILE_TOKEN " " NOVA_PYROWAVE_PROFILE_TOKEN,
                           map + "\na=fmtp:4294967395 " NOVA_PYROWAVE_PROFILE_TOKEN,
                           map + "\n" + profile + "\n" + map})
        require(!nova_pyrowave_description_compatible(bad.c_str()));
    std::cout << "PyroWave SDP profile lists, exact tokens, line endings and ambiguity rejection passed\n";
}
