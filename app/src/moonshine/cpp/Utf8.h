/* MetroVerse modifications (C) 2026 Rizklee. Licensed under GPL-3.0. */
#pragma once

#include <cstdint>
#include <string>
#include <string_view>

namespace metroverse {

// JNI NewStringUTF expects modified UTF-8. Model output is standard UTF-8,
// including supplementary characters, so convert explicitly to UTF-16.
inline std::u16string utf8ToUtf16(std::string_view text) {
    std::u16string result;
    for (size_t i = 0; i < text.size();) {
        const auto lead = static_cast<uint8_t>(text[i]);
        uint32_t codepoint = lead;
        size_t count = 1;
        uint32_t minimum = 0;
        if (lead >= 0xc2 && lead <= 0xdf) {
            codepoint = lead & 0x1f;
            count = 2;
            minimum = 0x80;
        } else if (lead >= 0xe0 && lead <= 0xef) {
            codepoint = lead & 0x0f;
            count = 3;
            minimum = 0x800;
        } else if (lead >= 0xf0 && lead <= 0xf4) {
            codepoint = lead & 0x07;
            count = 4;
            minimum = 0x10000;
        } else if (lead >= 0x80) {
            result.push_back(u'\ufffd');
            ++i;
            continue;
        }
        bool valid = i + count <= text.size();
        for (size_t j = 1; valid && j < count; ++j) {
            const auto next = static_cast<uint8_t>(text[i + j]);
            valid = (next & 0xc0) == 0x80;
            codepoint = (codepoint << 6) | (next & 0x3f);
        }
        valid = valid && codepoint >= minimum && codepoint <= 0x10ffff &&
            !(codepoint >= 0xd800 && codepoint <= 0xdfff);
        if (!valid) {
            result.push_back(u'\ufffd');
            ++i;
        } else {
            if (codepoint <= 0xffff) {
                result.push_back(static_cast<char16_t>(codepoint));
            } else {
                codepoint -= 0x10000;
                result.push_back(static_cast<char16_t>(0xd800 + (codepoint >> 10)));
                result.push_back(static_cast<char16_t>(0xdc00 + (codepoint & 0x3ff)));
            }
            i += count;
        }
    }
    return result;
}

inline std::string utf16ToUtf8(std::u16string_view text) {
    std::string result;
    for (size_t i = 0; i < text.size(); ++i) {
        uint32_t codepoint = text[i];
        if (codepoint >= 0xd800 && codepoint <= 0xdbff && i + 1 < text.size() &&
            text[i + 1] >= 0xdc00 && text[i + 1] <= 0xdfff) {
            codepoint = 0x10000 + ((codepoint - 0xd800) << 10) + (text[++i] - 0xdc00);
        } else if (codepoint >= 0xd800 && codepoint <= 0xdfff) {
            codepoint = 0xfffd;
        }
        if (codepoint < 0x80) {
            result.push_back(static_cast<char>(codepoint));
        } else if (codepoint < 0x800) {
            result.push_back(static_cast<char>(0xc0 | (codepoint >> 6)));
            result.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
        } else if (codepoint < 0x10000) {
            result.push_back(static_cast<char>(0xe0 | (codepoint >> 12)));
            result.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
            result.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
        } else {
            result.push_back(static_cast<char>(0xf0 | (codepoint >> 18)));
            result.push_back(static_cast<char>(0x80 | ((codepoint >> 12) & 0x3f)));
            result.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
            result.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
        }
    }
    return result;
}

}  // namespace metroverse
