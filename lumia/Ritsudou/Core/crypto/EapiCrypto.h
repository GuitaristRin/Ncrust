#pragma once

#include <string>

namespace ritsudou
{
    // 网易云音乐 eapi 参数加密。
    // 与 Android 版 EapiCrypto.kt 逐字节等价：
    //   urlPath = url.path 去掉 /eapi/ 前缀
    //   digest  = MD5("nobody" + urlPath + "use" + payloadJson + "md5forencrypt")
    //   params  = AES-128-ECB(urlPath + "-36cd479b6b5-" + payloadJson + "-36cd479b6b5-" + digest)
    //   return  = params 的小写 hex
    class EapiCrypto
    {
    public:
        static std::string encryptParams(const std::string& fullUrl, const std::string& payloadJson);

        static std::string md5Hex(const std::string& data);
        static std::string aes128EcbHex(const std::string& data, const std::string& key);
    };
}
