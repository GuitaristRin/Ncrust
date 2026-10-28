#include "pch.h"
#include "EapiCrypto.h"

#include <windows.h>
#include <bcrypt.h>

#include <vector>

#pragma comment(lib, "bcrypt.lib")

namespace ritsudou
{
    namespace
    {
        constexpr char kSeparator[] = "-36cd479b6b5-";
        constexpr char kMagicPrefix[] = "nobody";
        constexpr char kMagicSuffix[] = "md5forencrypt";
        const std::string kAesKey = "e82ckenh8dichen8";

        std::string bytesToHex(const unsigned char* bytes, size_t count)
        {
            static const char hex[] = "0123456789abcdef";
            std::string out;
            out.reserve(count * 2);
            for (size_t i = 0; i < count; ++i)
            {
                out.push_back(hex[bytes[i] >> 4]);
                out.push_back(hex[bytes[i] & 0x0F]);
            }
            return out;
        }

        // fullUrl 形如 "https://music.163.com/eapi/user/playlist"，取其 path。
        std::string urlPathOf(const std::string& fullUrl)
        {
            size_t scheme = fullUrl.find("://");
            size_t start = (scheme == std::string::npos) ? 0 : scheme + 3;
            size_t slash = fullUrl.find('/', start);
            std::string path = (slash == std::string::npos) ? "/" : fullUrl.substr(slash);
            if (path.rfind("/eapi/", 0) == 0)
            {
                path.replace(0, 6, "/api/");
            }
            return path;
        }
    }

    std::string EapiCrypto::md5Hex(const std::string& data)
    {
        std::vector<unsigned char> hash(16);
        BCRYPT_ALG_HANDLE alg = nullptr;
        NTSTATUS status = ::BCryptOpenAlgorithmProvider(&alg, BCRYPT_MD5_ALGORITHM, nullptr, 0);
        if (status < 0) throw std::runtime_error("BCryptOpenAlgorithmProvider(MD5) failed");

        status = ::BCryptHash(alg, nullptr, 0,
            reinterpret_cast<PUCHAR>(const_cast<char*>(data.empty() ? "" : data.data())),
            static_cast<ULONG>(data.size()),
            hash.data(), static_cast<ULONG>(hash.size()));
        ::BCryptCloseAlgorithmProvider(alg, 0);
        if (status < 0) throw std::runtime_error("BCryptHash(MD5) failed");

        return bytesToHex(hash.data(), hash.size());
    }

    std::string EapiCrypto::aes128EcbHex(const std::string& data, const std::string& key)
    {
        constexpr size_t kBlock = 16;
        const size_t padLen = kBlock - (data.size() % kBlock);
        std::vector<unsigned char> padded(data.begin(), data.end());
        padded.insert(padded.end(), padLen, static_cast<unsigned char>(padLen));

        BCRYPT_ALG_HANDLE alg = nullptr;
        BCRYPT_KEY_HANDLE keyHandle = nullptr;
        if (::BCryptOpenAlgorithmProvider(&alg, BCRYPT_AES_ALGORITHM, nullptr, 0) < 0)
            throw std::runtime_error("BCryptOpenAlgorithmProvider(AES) failed");

        ::BCryptSetProperty(alg, BCRYPT_CHAINING_MODE,
            reinterpret_cast<PUCHAR>(const_cast<wchar_t*>(BCRYPT_CHAIN_MODE_ECB)),
            sizeof(BCRYPT_CHAIN_MODE_ECB), 0);

        if (::BCryptGenerateSymmetricKey(alg, &keyHandle, nullptr, 0,
                reinterpret_cast<PUCHAR>(const_cast<char*>(key.data())),
                static_cast<ULONG>(key.size()), 0) < 0)
        {
            ::BCryptCloseAlgorithmProvider(alg, 0);
            throw std::runtime_error("BCryptGenerateSymmetricKey failed");
        }

        std::vector<unsigned char> out(padded.size() + kBlock);
        ULONG written = 0;
        NTSTATUS status = ::BCryptEncrypt(keyHandle,
            padded.data(), static_cast<ULONG>(padded.size()),
            nullptr, nullptr, 0,
            out.data(), static_cast<ULONG>(out.size()), &written, 0);

        ::BCryptDestroyKey(keyHandle);
        ::BCryptCloseAlgorithmProvider(alg, 0);

        if (status < 0) throw std::runtime_error("BCryptEncrypt(ECB) failed");
        out.resize(written);
        return bytesToHex(out.data(), out.size());
    }

    std::string EapiCrypto::encryptParams(const std::string& fullUrl, const std::string& payloadJson)
    {
        const std::string urlPath = urlPathOf(fullUrl);
        const std::string signStr = std::string(kMagicPrefix) + urlPath + "use" + payloadJson + kMagicSuffix;
        const std::string digest = md5Hex(signStr);
        const std::string paramsStr = urlPath + kSeparator + payloadJson + kSeparator + digest;
        return aes128EcbHex(paramsStr, kAesKey);
    }
}
