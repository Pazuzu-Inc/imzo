// The device key and answer counter, kept in NVS flash (demo only: no secure element, no flash encryption).
#pragma once

#include "imzo_core.h"

class DeviceKey : public imzo::Signer, public imzo::CounterStore {
public:
    /** Loads the P-256 key from flash, or generates and stores one on first boot. Throws ProtocolError("KEY ERROR"). */
    void begin();
    imzo::Bytes publicKey() const override { return pub_; }
    imzo::Bytes sign(const imzo::Bytes& message) override;
    /** Writes the new counter to flash and reads it back BEFORE returning (persist before show). */
    uint64_t next() override;
    uint64_t counter() const;
    /** Forget key and counter (factory reset). The device must be enrolled again afterwards. */
    void wipe();

private:
    void derivePublic();
    uint8_t d_[32] = {0};
    imzo::Bytes pub_;
};
