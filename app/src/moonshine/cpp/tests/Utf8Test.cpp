/* MetroVerse modifications (C) 2026 Rizklee. Licensed under GPL-3.0. */
#include "../Utf8.h"

#include <cassert>
#include <iostream>

int main() {
    using metroverse::utf16ToUtf8;
    using metroverse::utf8ToUtf16;
    assert(utf8ToUtf16(u8"字幕 café العربية 😀") == u"字幕 café العربية 😀");
    assert(utf16ToUtf8(u"字幕 café العربية 😀") == u8"字幕 café العربية 😀");
    assert(utf8ToUtf16(std::string("a\0b", 3)) == std::u16string(u"a\0b", 3));
    assert(utf8ToUtf16("\xc0\xaf") == u"\ufffd\ufffd");
    assert(utf8ToUtf16("\xf4\x90\x80\x80") == u"\ufffd\ufffd\ufffd\ufffd");
    assert(utf8ToUtf16("\xf0\x9f") == u"\ufffd\ufffd");
    assert(utf16ToUtf8(std::u16string(1, 0xd800)) == "\xef\xbf\xbd");
    assert(utf16ToUtf8(std::u16string(1, 0xdc00)) == "\xef\xbf\xbd");

    for (uint32_t value = 0; value <= 0x10ffff; ++value) {
        if (value >= 0xd800 && value <= 0xdfff) continue;
        std::u16string utf16;
        if (value < 0x10000) {
            utf16.push_back(static_cast<char16_t>(value));
        } else {
            utf16.push_back(static_cast<char16_t>(0xd800 + ((value - 0x10000) >> 10)));
            utf16.push_back(static_cast<char16_t>(0xdc00 + ((value - 0x10000) & 0x3ff)));
        }
        assert(utf8ToUtf16(utf16ToUtf8(utf16)) == utf16);
    }
    std::cout << "UTF-8/UTF-16 tests passed for all Unicode scalar values\n";
}
