/*
 *  Copyright 2017 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.classlib.java.util;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.DuplicateFormatFlagsException;
import java.util.FormatFlagsConversionMismatchException;
import java.util.Locale;
import java.util.UnknownFormatConversionException;

public final class TFormatter implements Closeable, Flushable {
    private Locale locale;
    private Appendable out;
    private IOException ioException;

    public TFormatter() {
        this(Locale.getDefault());
    }

    public TFormatter(Appendable a) {
        this(a, Locale.getDefault());
    }

    public TFormatter(Locale l) {
        this(new StringBuilder(), l);
    }

    public TFormatter(Appendable a, Locale l) {
        out = a;
        locale = l;
    }

    public TFormatter(PrintStream ps) {
        this(new OutputStreamWriter(ps));
    }

    public TFormatter(OutputStream os) {
        this(new OutputStreamWriter(os));
    }

    public TFormatter(OutputStream os, String csn) throws UnsupportedEncodingException {
        this(new OutputStreamWriter(os, csn));
    }

    public TFormatter(OutputStream os, String csn, Locale l) throws UnsupportedEncodingException {
        this(new OutputStreamWriter(os, csn), l);
    }

    public Locale locale() {
        requireOpen();
        return locale;
    }

    public Appendable out() {
        requireOpen();
        return out;
    }

    private void requireOpen() {
        if (out == null) {
            throw new TFormatterClosedException();
        }
    }

    @Override
    public String toString() {
        requireOpen();
        return out.toString();
    }

    @Override
    public void flush() {
        requireOpen();
        if (out instanceof Flushable) {
            try {
                ((Flushable) out).flush();
            } catch (IOException e) {
                ioException = e;
            }
        }
    }

    @Override
    public void close() {
        requireOpen();
        try {
            if (out instanceof Closeable) {
                ((Closeable) out).close();
            }
        } catch (IOException e) {
            ioException = e;
        } finally {
            out = null;
        }
    }

    public IOException ioException() {
        return ioException;
    }

    public TFormatter format(String format, Object... args) {
        return format(locale, format, args);
    }

    public TFormatter format(Locale l, String format, Object... args) {
        requireOpen();
        try {
            if (args == null) {
                args = new Object[1];
            }
            new FormatWriter(this, out, l, format, args).write();
        } catch (IOException e) {
            ioException = e;
        }
        return this;
    }

    static class FormatWriter {
        private static final String FORMAT_FLAGS = "--#+ 0,(<";
        private static final int MASK_FOR_GENERAL_FORMAT =
                TFormattableFlags.LEFT_JUSTIFY | TFormattableFlags.ALTERNATE
                        | TFormattableFlags.UPPERCASE | TFormattableFlags.PREVIOUS_ARGUMENT;
        private TFormatter formatter;
        Appendable out;
        Locale locale;
        String format;
        Object[] args;
        int index;
        int defaultArgumentIndex;
        int argumentIndex;
        int previousArgumentIndex;
        int width;
        int precision;
        int flags;

        FormatWriter(TFormatter formatter, Appendable out, Locale locale, String format, Object[] args) {
            this.formatter = formatter;
            this.out = out;
            this.locale = locale;
            this.format = format;
            this.args = args;
        }

        void write() throws IOException {
            while (true) {
                int next = format.indexOf('%', index);
                if (next < 0) {
                    out.append(format.substring(index));
                    break;
                }
                out.append(format.substring(index, next));
                index = next + 1;

                char specifier = parseFormatSpecifier();
                configureFormat();
                formatValue(specifier);
            }
        }

        private void formatValue(char specifier) throws IOException {
            switch (specifier) {
                case 'b':
                    formatBoolean(specifier, false);
                    break;
                case 'B':
                    formatBoolean(specifier, true);
                    break;
                case 'h':
                    formatHex(specifier, false);
                    break;
                case 'H':
                    formatHex(specifier, true);
                    break;
                case 's':
                    formatString(specifier, false);
                    break;
                case 'S':
                    formatString(specifier, true);
                    break;
                default:
                    throw new UnknownFormatConversionException(String.valueOf(specifier));
            }
        }

        private void formatBoolean(char specifier, boolean upperCase) throws IOException {
            verifyFlagsForGeneralFormat(specifier);
            Object arg = args[argumentIndex];
            String s = Boolean.toString(arg instanceof Boolean ? (Boolean) arg : arg != null);
            formatGivenString(upperCase, s);
        }

        private void formatHex(char specifier, boolean upperCase) throws IOException {
            verifyFlagsForGeneralFormat(specifier);
            Object arg = args[argumentIndex];
            String s = arg != null ? Integer.toHexString(arg.hashCode()) : "null";
            formatGivenString(upperCase, s);
        }

        private void formatString(char specifier, boolean upperCase) throws IOException {
            verifyFlagsForGeneralFormat(specifier);
            Object arg = args[argumentIndex];
            if (arg instanceof TFormattable) {
                int flagsToPass = flags & 7;
                if (upperCase) {
                    flagsToPass |= TFormattableFlags.UPPERCASE;
                }
                ((TFormattable) arg).formatTo(formatter, flagsToPass, width, precision);
            } else {
                formatGivenString(upperCase, String.valueOf(arg));
            }
        }

        private void formatGivenString(boolean upperCase, String str) throws IOException {
            if (precision > 0) {
                str = str.substring(0, precision);
            }

            if (upperCase) {
                str = str.toUpperCase();
            }

            if ((flags & TFormattableFlags.LEFT_JUSTIFY) != 0) {
                out.append(str);
                mayBeAppendSpaces(str);
            } else {
                mayBeAppendSpaces(str);
                out.append(str);
            }
        }

        private void verifyFlagsForGeneralFormat(char conversion) {
            verifyFlags(conversion, MASK_FOR_GENERAL_FORMAT);
        }

        private void verifyFlags(char conversion, int mask) {
            if ((flags | mask) != mask) {
                int flagIndex = Integer.numberOfTrailingZeros(flags & ~mask);
                throw new FormatFlagsConversionMismatchException(String.valueOf(FORMAT_FLAGS.charAt(flagIndex)),
                        conversion);
            }
        }

        private void mayBeAppendSpaces(String str) throws IOException {
            if (width > str.length()) {
                int diff = width - str.length();
                StringBuilder sb = new StringBuilder(diff);
                for (int i = 0; i < diff; ++i) {
                    sb.append(' ');
                }
                out.append(sb);
            }
        }

        private void configureFormat() {
            if ((flags & TFormattableFlags.PREVIOUS_ARGUMENT) != 0) {
                argumentIndex = Math.max(0, previousArgumentIndex);
            }

            if (argumentIndex == -1) {
                argumentIndex = defaultArgumentIndex++;
            }
            previousArgumentIndex = argumentIndex;
        }

        private char parseFormatSpecifier() {
            flags = 0;
            argumentIndex = -1;
            width = -1;
            precision = -1;

            char c = format.charAt(index);

            if (c != '0' && isDigit(c)) {
                int n = readInt();
                if (index < format.length() && format.charAt(index) == '$') {
                    index++;
                    argumentIndex = n - 1;
                } else {
                    width = n;
                }
            }
            parseFlags();

            if (width < 0 && index < format.length() && isDigit(format.charAt(index))) {
                width = readInt();
            }

            if (index < format.length() && format.charAt(index) == '.') {
                index++;
                if (index >= format.length() || !isDigit(format.charAt(index))) {
                    throw new UnknownFormatConversionException(String.valueOf(format.charAt(index - 1)));
                }
                precision = readInt();
            }

            if (index >= format.length()) {
                throw new UnknownFormatConversionException(String.valueOf(format.charAt(format.length() - 1)));
            }
            return format.charAt(index++);
        }

        private void parseFlags() {
            while (index < format.length()) {
                char c = format.charAt(index);
                int flag;
                switch (c) {
                    case '-':
                        flag = TFormattableFlags.LEFT_JUSTIFY;
                        break;
                    case '#':
                        flag = TFormattableFlags.ALTERNATE;
                        break;
                    case '+':
                        flag = TFormattableFlags.SIGNED;
                        break;
                    case ' ':
                        flag = TFormattableFlags.LEADING_SPACE;
                        break;
                    case '0':
                        flag = TFormattableFlags.ZERO_PADDED;
                        break;
                    case ',':
                        flag = TFormattableFlags.GROUPING_SEPARATOR;
                        break;
                    case '(':
                        flag = TFormattableFlags.PARENTHESIZED_NEGATIVE;
                        break;
                    case '<':
                        flag = TFormattableFlags.PREVIOUS_ARGUMENT;
                        break;
                    default:
                        return;
                }
                if ((flags & flag) != 0) {
                    throw new DuplicateFormatFlagsException(String.valueOf(c));
                }
                flags |= flag;
                index++;
            }
        }

        private int readInt() {
            int result = 0;
            while (index < format.length() && isDigit(format.charAt(index))) {
                result = result * 10 + (format.charAt(index++) - '0');
            }
            return result;
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }
    }
}
