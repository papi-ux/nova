#include "identity/deck_pairing_crypto.h"

#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/rand.h>
#include <openssl/rsa.h>
#include <openssl/x509.h>

#include <memory>

namespace nova::deck::identity::crypto {
namespace {
template<class T, auto Free> using Ptr = std::unique_ptr<T, decltype(Free)>;
using Certificate = Ptr<X509, X509_free>;
using Key = Ptr<EVP_PKEY, EVP_PKEY_free>;
Certificate readCertificate(const QByteArray& pem) {
    Ptr<BIO, BIO_free> bio(BIO_new_mem_buf(pem.constData(), pem.size()), BIO_free);
    return Certificate(bio ? PEM_read_bio_X509(bio.get(), nullptr, nullptr, nullptr) : nullptr, X509_free);
}
Key readKey(const QByteArray& pem) {
    Ptr<BIO, BIO_free> bio(BIO_new_mem_buf(pem.constData(), pem.size()), BIO_free);
    // Never let OpenSSL prompt for a passphrase on stdin.
    auto noPassword = [](char*, int, int, void*) { return 0; };
    return Key(bio ? PEM_read_bio_PrivateKey(bio.get(), nullptr, noPassword, nullptr) : nullptr, EVP_PKEY_free);
}
QByteArray bioBytes(BIO* bio) {
    char* bytes = nullptr;
    const auto length = BIO_get_mem_data(bio, &bytes);
    return length > 0 ? QByteArray(bytes, length) : QByteArray{};
}
}

QByteArray random(int size) {
    if (size <= 0 || size > 65536) return {};
    QByteArray bytes(size, Qt::Uninitialized);
    return RAND_bytes(reinterpret_cast<unsigned char*>(bytes.data()), size) == 1 ? bytes : QByteArray{};
}

std::optional<Credentials> generate() {
    Ptr<EVP_PKEY_CTX, EVP_PKEY_CTX_free> context(EVP_PKEY_CTX_new_id(EVP_PKEY_RSA, nullptr), EVP_PKEY_CTX_free);
    EVP_PKEY* generated = nullptr;
    if (!context || EVP_PKEY_keygen_init(context.get()) <= 0 ||
        EVP_PKEY_CTX_set_rsa_keygen_bits(context.get(), 2048) <= 0 ||
        EVP_PKEY_keygen(context.get(), &generated) <= 0) return std::nullopt;
    Key key(generated, EVP_PKEY_free);
    Certificate cert(X509_new(), X509_free);
    auto serial = random(16);
    if (!cert || serial.size() != 16) return std::nullopt;
    serial[0] = static_cast<char>((static_cast<unsigned char>(serial[0]) & 0x7f) | 1);
    Ptr<BIGNUM, BN_free> number(BN_bin2bn(reinterpret_cast<const unsigned char*>(serial.constData()), serial.size(), nullptr), BN_free);
    Ptr<ASN1_INTEGER, ASN1_INTEGER_free> serialNumber(number ? BN_to_ASN1_INTEGER(number.get(), nullptr) : nullptr, ASN1_INTEGER_free);
    auto* name = X509_get_subject_name(cert.get());
    if (!serialNumber || !X509_set_version(cert.get(), 2) || !X509_set_serialNumber(cert.get(), serialNumber.get()) ||
        !X509_gmtime_adj(X509_getm_notBefore(cert.get()), -300) ||
        !X509_gmtime_adj(X509_getm_notAfter(cert.get()), 20L * 365 * 24 * 60 * 60) ||
        !X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_ASC,
            reinterpret_cast<const unsigned char*>("Nova Deck Client"), -1, -1, 0) ||
        !X509_set_issuer_name(cert.get(), name) || !X509_set_pubkey(cert.get(), key.get()) ||
        X509_sign(cert.get(), key.get(), EVP_sha256()) <= 0) return std::nullopt;
    Ptr<BIO, BIO_free> certBio(BIO_new(BIO_s_mem()), BIO_free), keyBio(BIO_new(BIO_s_mem()), BIO_free);
    if (!certBio || !keyBio || !PEM_write_bio_X509(certBio.get(), cert.get()) ||
        !PEM_write_bio_PrivateKey(keyBio.get(), key.get(), nullptr, nullptr, 0, nullptr, nullptr)) return std::nullopt;
    return Credentials{bioBytes(certBio.get()), bioBytes(keyBio.get())};
}

bool valid(const Credentials& credentials) {
    auto cert = readCertificate(credentials.certificate);
    auto key = readKey(credentials.privateKey);
    return cert && key && EVP_PKEY_base_id(key.get()) == EVP_PKEY_RSA && EVP_PKEY_bits(key.get()) >= 2048 &&
        X509_check_private_key(cert.get(), key.get()) == 1 &&
        X509_cmp_current_time(X509_get0_notBefore(cert.get())) < 0 &&
        X509_cmp_current_time(X509_get0_notAfter(cert.get())) > 0;
}

QByteArray certificateDer(const QByteArray& pem) {
    auto cert = readCertificate(pem);
    const int size = cert ? i2d_X509(cert.get(), nullptr) : 0;
    if (size <= 0) return {};
    QByteArray bytes(size, Qt::Uninitialized);
    auto* cursor = reinterpret_cast<unsigned char*>(bytes.data());
    return i2d_X509(cert.get(), &cursor) == size ? bytes : QByteArray{};
}

QByteArray certificateSignature(const QByteArray& pem) {
    auto cert = readCertificate(pem);
    if (!cert) return {};
    const ASN1_BIT_STRING* signature = nullptr;
    X509_get0_signature(&signature, nullptr, cert.get());
    return signature ? QByteArray(reinterpret_cast<const char*>(signature->data), signature->length) : QByteArray{};
}

QByteArray sha256(const QByteArray& bytes) {
    QByteArray digest(EVP_MAX_MD_SIZE, Qt::Uninitialized);
    unsigned int size = 0;
    if (EVP_Digest(bytes.constData(), bytes.size(), reinterpret_cast<unsigned char*>(digest.data()),
                   &size, EVP_sha256(), nullptr) != 1) return {};
    digest.resize(size);
    return digest;
}

QByteArray aes(const QByteArray& bytes, const QByteArray& key, bool encrypt) {
    if (bytes.isEmpty() || bytes.size() % 16 != 0 || key.size() != 16) return {};
    Ptr<EVP_CIPHER_CTX, EVP_CIPHER_CTX_free> ctx(EVP_CIPHER_CTX_new(), EVP_CIPHER_CTX_free);
    QByteArray out(bytes.size() + 16, Qt::Uninitialized);
    int size = 0, finalSize = 0;
    if (!ctx || EVP_CipherInit_ex(ctx.get(), EVP_aes_128_ecb(), nullptr,
        reinterpret_cast<const unsigned char*>(key.constData()), nullptr, encrypt ? 1 : 0) != 1 ||
        EVP_CIPHER_CTX_set_padding(ctx.get(), 0) != 1 ||
        EVP_CipherUpdate(ctx.get(), reinterpret_cast<unsigned char*>(out.data()), &size,
            reinterpret_cast<const unsigned char*>(bytes.constData()), bytes.size()) != 1 ||
        EVP_CipherFinal_ex(ctx.get(), reinterpret_cast<unsigned char*>(out.data()) + size, &finalSize) != 1) return {};
    out.resize(size + finalSize);
    return out;
}

QByteArray sign(const QByteArray& bytes, const QByteArray& privateKey) {
    auto key = readKey(privateKey);
    Ptr<EVP_MD_CTX, EVP_MD_CTX_free> ctx(EVP_MD_CTX_new(), EVP_MD_CTX_free);
    size_t size = 0;
    if (!key || !ctx || EVP_DigestSignInit(ctx.get(), nullptr, EVP_sha256(), nullptr, key.get()) != 1 ||
        EVP_DigestSign(ctx.get(), nullptr, &size, reinterpret_cast<const unsigned char*>(bytes.constData()), bytes.size()) != 1) return {};
    QByteArray signature(size, Qt::Uninitialized);
    if (EVP_DigestSign(ctx.get(), reinterpret_cast<unsigned char*>(signature.data()), &size,
        reinterpret_cast<const unsigned char*>(bytes.constData()), bytes.size()) != 1) return {};
    signature.resize(size);
    return signature;
}

bool verify(const QByteArray& bytes, const QByteArray& signature, const QByteArray& certificate) {
    auto cert = readCertificate(certificate);
    Key key(cert ? X509_get_pubkey(cert.get()) : nullptr, EVP_PKEY_free);
    Ptr<EVP_MD_CTX, EVP_MD_CTX_free> ctx(EVP_MD_CTX_new(), EVP_MD_CTX_free);
    return key && ctx && EVP_DigestVerifyInit(ctx.get(), nullptr, EVP_sha256(), nullptr, key.get()) == 1 &&
        EVP_DigestVerify(ctx.get(), reinterpret_cast<const unsigned char*>(signature.constData()), signature.size(),
            reinterpret_cast<const unsigned char*>(bytes.constData()), bytes.size()) == 1;
}

bool equal(const QByteArray& first, const QByteArray& second) {
    return !first.isEmpty() && first.size() == second.size() &&
        CRYPTO_memcmp(first.constData(), second.constData(), first.size()) == 0;
}
} // namespace nova::deck::identity::crypto
