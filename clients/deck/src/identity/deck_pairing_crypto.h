#pragma once

#include <QByteArray>
#include <optional>

namespace nova::deck::identity::crypto {

struct Credentials {
    QByteArray certificate;
    QByteArray privateKey;
};

std::optional<Credentials> generate();
bool valid(const Credentials& credentials);
QByteArray certificateDer(const QByteArray& pem);
QByteArray certificateSignature(const QByteArray& pem);
QByteArray random(int size);
QByteArray sha256(const QByteArray& bytes);
// GameStream generation 7 uses AES-128-ECB with no padding, by protocol.
QByteArray aes(const QByteArray& bytes, const QByteArray& key, bool encrypt);
QByteArray sign(const QByteArray& bytes, const QByteArray& privateKey);
bool verify(const QByteArray& bytes, const QByteArray& signature, const QByteArray& certificate);
bool equal(const QByteArray& first, const QByteArray& second);

} // namespace nova::deck::identity::crypto
