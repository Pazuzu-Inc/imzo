// Screen for the Imzo Device (536x240 AMOLED, landscape). Pure drawing: no protocol decisions in here.
#pragma once

#include <string>
#include <vector>

#include "imzo_core.h"

class Ui {
public:
    void begin();

    void showIdle(const std::string& deviceShortId, uint64_t counter);
    void showProgress(const std::string& kind, int have, int count);
    void showError(const std::string& code, const std::string& hint);
    void showMessage(const std::string& title, const std::string& line);

    // Review pages. page 0 = summary, 1..count = one beneficiary each, count+1 = decision.
    void showReviewPage(const imzo::Review& r, size_t page);
    /** Three dots at the bottom: `count` of the 3 clicks made so far in the current series. */
    void showClicks(int count);

    /** "Are you sure?" page after the decision was chosen. */
    void showConfirm(const imzo::Review& r, bool approve);

    void showEnrolCode(const std::string& code, const std::string& org);
    /** One QR frame of an answer. `title` says what to do with it; `index`/`count` count the frames. */
    void showQr(const std::string& title, const std::string& text, size_t index, size_t count);

    void setBrightness(uint8_t v);
};
