package rlvm.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Hand-written edge cases with the expected verdict of the reader, checked three ways:
 * (1) all configurations of {@link ExportReader} and the reference reader agree with the table;
 * (2) the official reader ({@code leanchecker --from-export} of Lean v4.36.0), when installed, agrees
 *     with the table as to whether the file <em>parses</em> (kernel verdicts are ignored).
 *
 * <p>Expected verdicts: {@code ok}; {@code M<line>} malformed at that line (the official reader fails
 * too); {@code Ua<line>} unsupported here while the official reader parses the file;
 * {@code Ur<line>} unsupported here while the official reader fails; {@code U?<line>} unsupported here,
 * official behaviour not asserted (it depends on how Lean decodes ill-formed UTF-8).
 *
 * <p>Case texts are Latin-1 strings: every char is one byte of the file. Line 1 is the header.
 */
class EdgeCaseTest {
    private record Case(String name, String expected, byte[] file) {}

    private static final List<Case> CASES = new ArrayList<>();

    private static void raw(String name, String expected, String wholeFile) {
        CASES.add(new Case(name, expected, T.latin1(wholeFile)));
    }

    /** A case whose file is the header followed by {@code body}. */
    private static void c(String name, String expected, String body) {
        raw(name, expected, T.META + body);
    }

    private static final String N1 = "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\"}}\n";
    private static final String BIG = "123456789012345678901234567890";
    private static final String TWO63 = "9223372036854775808";

    // names 1 (a), 2 (b); level 1 = succ 0; expr 0 = Sort 1, expr 1 = Sort 0 (Prop)
    private static final String PRE = N1
            + "{\"in\":2,\"str\":{\"pre\":0,\"str\":\"b\"}}\n"
            + "{\"il\":1,\"succ\":0}\n"
            + "{\"ie\":0,\"sort\":1}\n"
            + "{\"ie\":1,\"sort\":0}\n";
    // the block of Bool from tutorial/037 (lines 2..35 of the file; the inductive line is line 35)
    private static final String BOOL_PRE = "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"Bool\"}}\n"
            + "{\"il\":1,\"succ\":0}\n"
            + "{\"ie\":0,\"sort\":1}\n"
            + "{\"in\":2,\"str\":{\"pre\":1,\"str\":\"false\"}}\n"
            + "{\"in\":3,\"str\":{\"pre\":1,\"str\":\"true\"}}\n"
            + "{\"const\":{\"name\":1,\"us\":[]},\"ie\":1}\n"
            + "{\"in\":4,\"str\":{\"pre\":1,\"str\":\"rec\"}}\n"
            + "{\"in\":5,\"str\":{\"pre\":0,\"str\":\"u\"}}\n"
            + "{\"il\":2,\"param\":5}\n"
            + "{\"in\":6,\"str\":{\"pre\":0,\"str\":\"motive\"}}\n"
            + "{\"in\":7,\"str\":{\"pre\":0,\"str\":\"t\"}}\n"
            + "{\"ie\":2,\"sort\":2}\n"
            + "{\"forallE\":{\"binderInfo\":\"default\",\"body\":2,\"name\":7,\"type\":1},\"ie\":3}\n"
            + "{\"in\":8,\"str\":{\"pre\":0,\"str\":\"false\"}}\n"
            + "{\"bvar\":0,\"ie\":4}\n"
            + "{\"const\":{\"name\":2,\"us\":[]},\"ie\":5}\n"
            + "{\"app\":{\"arg\":5,\"fn\":4},\"ie\":6}\n"
            + "{\"in\":9,\"str\":{\"pre\":0,\"str\":\"true\"}}\n"
            + "{\"bvar\":1,\"ie\":7}\n"
            + "{\"const\":{\"name\":3,\"us\":[]},\"ie\":8}\n"
            + "{\"app\":{\"arg\":8,\"fn\":7},\"ie\":9}\n"
            + "{\"bvar\":3,\"ie\":10}\n"
            + "{\"app\":{\"arg\":4,\"fn\":10},\"ie\":11}\n"
            + "{\"forallE\":{\"binderInfo\":\"default\",\"body\":11,\"name\":7,\"type\":1},\"ie\":12}\n"
            + "{\"forallE\":{\"binderInfo\":\"default\",\"body\":12,\"name\":9,\"type\":9},\"ie\":13}\n"
            + "{\"forallE\":{\"binderInfo\":\"default\",\"body\":13,\"name\":8,\"type\":6},\"ie\":14}\n"
            + "{\"forallE\":{\"binderInfo\":\"implicit\",\"body\":14,\"name\":6,\"type\":3},\"ie\":15}\n"
            + "{\"ie\":16,\"lam\":{\"binderInfo\":\"default\",\"body\":7,\"name\":9,\"type\":9}}\n"
            + "{\"ie\":17,\"lam\":{\"binderInfo\":\"default\",\"body\":16,\"name\":8,\"type\":6}}\n"
            + "{\"ie\":18,\"lam\":{\"binderInfo\":\"default\",\"body\":17,\"name\":6,\"type\":3}}\n"
            + "{\"ie\":19,\"lam\":{\"binderInfo\":\"default\",\"body\":4,\"name\":9,\"type\":9}}\n"
            + "{\"ie\":20,\"lam\":{\"binderInfo\":\"default\",\"body\":19,\"name\":8,\"type\":6}}\n"
            + "{\"ie\":21,\"lam\":{\"binderInfo\":\"default\",\"body\":20,\"name\":6,\"type\":3}}\n";
    private static final String CTOR_F = "{\"cidx\":0,\"induct\":1,\"isUnsafe\":false,\"levelParams\":[],\"name\":2,\"numFields\":0,\"numParams\":0,\"type\":1}";
    private static final String CTOR_T = "{\"cidx\":1,\"induct\":1,\"isUnsafe\":false,\"levelParams\":[],\"name\":3,\"numFields\":0,\"numParams\":0,\"type\":1}";
    private static final String RULES = "[{\"ctor\":2,\"nfields\":0,\"rhs\":18},{\"ctor\":3,\"nfields\":0,\"rhs\":21}]";
    private static final String REC = "{\"all\":[1],\"isUnsafe\":false,\"k\":false,\"levelParams\":[5],\"name\":4,\"numIndices\":0,\"numMinors\":2,\"numMotives\":1,\"numParams\":0,\"rules\":" + RULES + ",\"type\":15}";
    private static final String TYPE = "{\"all\":[1],\"ctors\":[2,3],\"isRec\":false,\"isReflexive\":false,\"isUnsafe\":false,\"levelParams\":[],\"name\":1,\"numIndices\":0,\"numNested\":0,\"numParams\":0,\"type\":0}";

    private static String ind(String ctors, String recs, String types) {
        return BOOL_PRE + "{\"inductive\":{\"ctors\":" + ctors + ",\"recs\":" + recs + ",\"types\":" + types + "}}\n";
    }

    private static String deep(int depth) {
        return "[".repeat(depth) + "]".repeat(depth);
    }

    static {
        // ---------------------------------------------------------------- file structure
        raw("empty file", "ok", "");
        raw("header only, no newline", "ok", T.META.trim());
        raw("header only", "ok", T.META);
        raw("garbage header", "ok", "this is not json\n" + N1);
        raw("header is a record and is swallowed", "M2", N1 + "{\"in\":2,\"str\":{\"pre\":1,\"str\":\"b\"}}\n");
        raw("ill-formed UTF-8 in the header is ignored", "ok", "{\"meta\":\"\u00ff\"}\n" + N1);
        raw("byte order mark before the header", "ok", "\u00ef\u00bb\u00bf" + T.META + N1);
        c("byte order mark on line 2", "M2", "\u00ef\u00bb\u00bf" + N1);
        c("blank line in the middle", "M3", N1 + "\n" + "{\"in\":2,\"str\":{\"pre\":1,\"str\":\"b\"}}\n");
        c("blank line at the end", "M3", N1 + "\n");
        c("whitespace-only line", "M3", N1 + "  \t \n");
        c("CR LF line ends", "ok", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\"}}\r\n{\"in\":2,\"str\":{\"pre\":1,\"str\":\"b\"}}\r\n");
        c("no final newline", "ok", N1 + "{\"in\":2,\"str\":{\"pre\":1,\"str\":\"b\"}}");
        c("no final newline, canonical app", "ok", "{\"bvar\":0,\"ie\":0}\n{\"app\":{\"arg\":0,\"fn\":0},\"ie\":1}");
        c("last line is cut off", "M3", N1 + "{\"in\":2,\"str\":{\"pre\":1,\"st");
        c("CR as the only separator", "M2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\"}}\r{\"in\":2,\"str\":{\"pre\":1,\"str\":\"b\"}}\n");
        c("two records on one line", "M2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\"}}{\"in\":2,\"str\":{\"pre\":1,\"str\":\"b\"}}\n");
        c("text after the record", "M2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\"}} x\n");
        c("form feed after the record", "M2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\"}}\f\n");
        c("NUL after the record", "M2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\"}}\u0000\n");
        c("NUL between tokens", "M2", "{\"in\":1,\u0000\"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        c("NUL in a string", "M2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\u0000b\"}}\n");
        c("whitespace everywhere", "ok", " \t{ \"in\" : 1 ,\r \"str\" : { \"pre\" : 0 , \"str\" : \"a\" } } \t\r\n");
        c("space before the record of a canonical line", "ok", " {\"bvar\":0,\"ie\":0}\n {\"app\":{\"arg\":0,\"fn\":0},\"ie\":1}\n");
        c("space inside a canonical app line", "ok", "{\"bvar\":0,\"ie\":0}\n{\"app\":{\"arg\":0, \"fn\":0},\"ie\":1}\n");
        // ---------------------------------------------------------------- JSON values
        c("top level array", "M2", "[1]\n");
        c("top level string", "M2", "\"in\"\n");
        c("top level number", "M2", "1\n");
        c("top level null", "M2", "null\n");
        c("top level true", "M2", "true\n");
        c("empty object", "M2", "{}\n");
        c("unknown single key", "M2", "{\"foo\":{}}\n");
        c("unknown key pair", "M2", "{\"ie\":5,\"in\":3}\n");
        c("two id keys", "M2", "{\"il\":1,\"in\":1}\n");
        c("extra top-level key", "M2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\"},\"zzz\":1}\n");
        c("extra nested key", "ok", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\",\"zzz\":null}}\n");
        c("extra nested structure", "ok", "{\"in\":1,\"str\":{\"aaa\":[1,2.5,-3e-2,{\"x\":[]},\"s\",true,false,null],\"pre\":0,\"str\":\"a\"}}\n");
        c("duplicate nested key", "M2", "{\"in\":1,\"str\":{\"pre\":0,\"pre\":0,\"str\":\"a\"}}\n");
        c("duplicate top-level key", "M2", "{\"in\":1,\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        c("duplicate key through an escape", "M2", "{\"in\":1,\"str\":{\"pre\":0,\"p\\u0072e\":0,\"str\":\"a\"}}\n");
        c("duplicate key in an ignored object", "M2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\",\"x\":{\"k\":1,\"k\":2}}}\n");
        c("duplicate key in a wide ignored object", "M2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\",\"x\":{" + wideKeys(40) + ",\"k7\":0}}}\n");
        c("wide ignored object without duplicates", "ok", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\",\"x\":{" + wideKeys(40) + "}}}\n");
        c("escaped key", "ok", "{\"\\u0069n\":1,\"s\\u0074r\":{\"pr\\u0065\":0,\"str\":\"a\"}}\n");
        c("keys in another order", "ok", "{\"str\":{\"str\":\"a\",\"pre\":0},\"in\":1}\n");
        c("trailing comma in object", "M2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\",}}\n");
        c("trailing comma in array", "M3", N1 + "{\"il\":1,\"max\":[0,0,]}\n");
        c("missing comma", "M2", "{\"in\":1 \"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        c("missing colon", "M2", "{\"in\" 1,\"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        c("single quotes", "M2", "{'in':1,'str':{'pre':0,'str':'a'}}\n");
        c("unquoted key", "M2", "{in:1,\"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        c("unterminated string", "M2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a}}\n");
        c("unbalanced braces", "M2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\"}\n");
        c("too many closing braces", "M2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\"}}}\n");
        c("capitalised literal", "M3", "{\"bvar\":0,\"ie\":0}\n{\"ie\":1,\"letE\":{\"body\":0,\"name\":0,\"nondep\":False,\"type\":0,\"value\":0}}\n");
        c("literal with a tail", "M3", "{\"bvar\":0,\"ie\":0}\n{\"ie\":1,\"letE\":{\"body\":0,\"name\":0,\"nondep\":falsey,\"type\":0,\"value\":0}}\n");
        c("deeply nested ignored value", "ok", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\",\"x\":" + deep(20000) + "}}\n");
        c("deeply nested unbalanced value", "M2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\",\"x\":" + deep(3000).substring(1) + "}}\n");
        // ---------------------------------------------------------------- strings
        c("raw tab in a string", "M2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\tb\"}}\n");
        c("raw DEL in a string", "ok", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\u007fb\"}}\n");
        c("all simple escapes", "ok", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"\\\\ \\\" \\/ \\b \\f \\n \\r \\t\"}}\n");
        c("escaped NUL", "ok", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\\u0000b\"}}\n");
        c("upper case hex", "ok", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"\\u00E9\\u00e9\\uABCD\"}}\n");
        c("unknown escape", "M2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\\xb\"}}\n");
        c("escape U", "M2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"\\U0041\"}}\n");
        c("short unicode escape", "M2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"\\u12\"}}\n");
        c("bad hex digit", "M2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"\\u12g4\"}}\n");
        c("backslash at the end of the string", "M2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\\\"}}\n");
        c("surrogate pair escape", "ok", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"\\ud83d\\ude00\"}}\n");
        c("lone high surrogate escape", "ok", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"\\ud83d\"}}\n");
        c("lone low surrogate escape", "ok", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"\\ude00\"}}\n");
        c("high surrogate then a letter escape", "ok", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"\\ud83d\\u0041\"}}\n");
        c("high surrogate then a broken escape", "M2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"\\ud83d\\udx00\"}}\n");
        c("high surrogate at the end of the line", "M2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"\\ud83d");
        c("two byte UTF-8", "ok", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"\u00ce\u00b1\"}}\n");
        c("three and four byte UTF-8", "ok", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"\u00e2\u0082\u00ac\u00f0\u009f\u0098\u0080\"}}\n");
        c("UTF-8 in a key of an ignored member", "ok", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\",\"\u00ce\u00b1\":1}}\n");
        c("byte FF in a string", "Ua2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"\u00ff\"}}\n");
        c("truncated UTF-8 sequence", "Ua2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"\u00c3\"}}\n");
        c("overlong UTF-8", "Ua2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"\u00c0\u0080\"}}\n");
        c("UTF-8 encoded surrogate", "Ua2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"\u00ed\u00a0\u0080\"}}\n");
        c("UTF-8 beyond U+10FFFF", "Ua2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"\u00f4\u0090\u0080\u0080\"}}\n");
        c("lone continuation byte", "Ua2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\u0080b\"}}\n");
        c("ill-formed UTF-8 outside strings", "U?2", "{\"in\":1,\u00ff\"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        c("ill-formed UTF-8 after a syntax error", "U?2", "{\"in\":1,,\"str\":{\"pre\":0,\"str\":\"\u00ff\"}}\n");
        c("non-ASCII outside strings", "M2", "{\"in\":1,\u00ce\u00b1\"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        c("non-breaking space as whitespace", "M2", "{\"in\":1,\u00c2\u00a0\"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        c("long string", "ok", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"" + "x".repeat(300000) + "\"}}\n" + "{\"in\":2,\"str\":{\"pre\":1,\"str\":\"b\"}}\n");
        // ---------------------------------------------------------------- numbers
        c("exponent form 1e0", "ok", "{\"in\":1e0,\"str\":{\"pre\":0,\"str\":\"a\"}}\n{\"in\":2,\"str\":{\"pre\":1,\"str\":\"b\"}}\n");
        c("exponent form 1E+0", "ok", "{\"in\":1E+0,\"str\":{\"pre\":0e5,\"str\":\"a\"}}\n{\"in\":2,\"str\":{\"pre\":1,\"str\":\"b\"}}\n");
        c("1.0 is not a natural", "M2", "{\"in\":1.0,\"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        c("0.0 is not a natural", "M2", "{\"in\":1,\"str\":{\"pre\":0.0,\"str\":\"a\"}}\n");
        c("minus zero is zero", "ok", "{\"in\":1,\"str\":{\"pre\":-0,\"str\":\"a\"}}\n");
        c("minus zero with exponent", "ok", "{\"in\":1,\"str\":{\"pre\":-0.0e1,\"str\":\"a\"}}\n");
        c("1.0e1 is ten", "ok", "{\"in\":1.0e1,\"str\":{\"pre\":0,\"str\":\"a\"}}\n{\"in\":2,\"str\":{\"pre\":10,\"str\":\"b\"}}\n");
        c("1.0e1 is not one", "M3", "{\"in\":1.0e1,\"str\":{\"pre\":0,\"str\":\"a\"}}\n{\"in\":2,\"str\":{\"pre\":1,\"str\":\"b\"}}\n");
        c("10e-1 is not a natural", "M2", "{\"in\":10e-1,\"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        c("1e-0 is one", "ok", "{\"in\":1e-0,\"str\":{\"pre\":0,\"str\":\"a\"}}\n{\"in\":2,\"str\":{\"pre\":1,\"str\":\"b\"}}\n");
        c("1e-000 is one", "ok", "{\"in\":1e-000,\"str\":{\"pre\":0,\"str\":\"a\"}}\n{\"in\":2,\"str\":{\"pre\":1,\"str\":\"b\"}}\n");
        c("0.1e1 is one", "ok", "{\"in\":0.1e1,\"str\":{\"pre\":0,\"str\":\"a\"}}\n{\"in\":2,\"str\":{\"pre\":1,\"str\":\"b\"}}\n");
        c("0.10e1 is not a natural", "M2", "{\"in\":0.10e1,\"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        c("0.10e2 is ten", "ok", "{\"in\":0.10e2,\"str\":{\"pre\":0,\"str\":\"a\"}}\n{\"in\":2,\"str\":{\"pre\":10,\"str\":\"b\"}}\n");
        c("1.5e1 is fifteen", "ok", "{\"in\":1.5e1,\"str\":{\"pre\":0,\"str\":\"a\"}}\n{\"in\":2,\"str\":{\"pre\":15,\"str\":\"b\"}}\n");
        c("12e3 is twelve thousand", "ok", "{\"in\":12e3,\"str\":{\"pre\":0,\"str\":\"a\"}}\n{\"in\":2,\"str\":{\"pre\":12000,\"str\":\"b\"}}\n");
        c("leading zero", "M2", "{\"in\":01,\"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        c("leading zero in a canonical app line", "M3", "{\"bvar\":0,\"ie\":0}\n{\"app\":{\"arg\":00,\"fn\":0},\"ie\":1}\n");
        c("plus sign", "M2", "{\"in\":+1,\"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        c("negative id", "M2", "{\"in\":-1,\"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        c("lone minus", "M2", "{\"in\":-,\"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        c("dot without digits", "M2", "{\"in\":1.,\"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        c("leading dot", "M2", "{\"in\":.5,\"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        c("exponent without digits", "M2", "{\"in\":1e,\"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        c("exponent sign without digits", "M2", "{\"in\":1e+,\"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        c("exponent too large", "M2", "{\"in\":1e100001,\"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        c("largest exponent on zero", "ok", "{\"in\":1,\"str\":{\"pre\":0e100000,\"str\":\"a\"}}\n");
        c("huge negative exponent", "M2", "{\"in\":1,\"str\":{\"pre\":0e-99999999999999999999,\"str\":\"a\"}}\n");
        c("hexadecimal", "M2", "{\"in\":0x1,\"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        c("id as a string", "M2", "{\"in\":\"1\",\"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        c("id null", "M2", "{\"in\":null,\"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        // ---------------------------------------------------------------- ids
        c("undefined name", "M2", "{\"in\":1,\"str\":{\"pre\":5,\"str\":\"a\"}}\n");
        c("name refers to itself", "M2", "{\"in\":1,\"str\":{\"pre\":1,\"str\":\"a\"}}\n");
        c("name bound twice", "M3", N1 + N1);
        c("implicit name 0 rebound", "M2", "{\"in\":0,\"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        c("implicit level 0 rebound", "M2", "{\"il\":0,\"succ\":0}\n");
        c("level bound twice", "M3", "{\"il\":1,\"succ\":0}\n{\"il\":1,\"succ\":0}\n");
        c("expr bound twice", "M3", "{\"bvar\":0,\"ie\":0}\n{\"bvar\":1,\"ie\":0}\n");
        c("expr bound twice, canonical app", "M4", "{\"bvar\":0,\"ie\":0}\n{\"app\":{\"arg\":0,\"fn\":0},\"ie\":1}\n{\"app\":{\"arg\":0,\"fn\":0},\"ie\":1}\n");
        c("forward reference", "M2", "{\"app\":{\"arg\":1,\"fn\":1},\"ie\":0}\n{\"bvar\":0,\"ie\":1}\n");
        c("self reference", "M2", "{\"app\":{\"arg\":0,\"fn\":0},\"ie\":0}\n");
        c("sparse ids", "ok", "{\"in\":7,\"str\":{\"pre\":0,\"str\":\"a\"}}\n{\"in\":3,\"str\":{\"pre\":7,\"str\":\"b\"}}\n{\"bvar\":0,\"ie\":40}\n{\"app\":{\"arg\":40,\"fn\":40},\"ie\":2}\n");
        c("id 2^63-1", "ok", "{\"in\":9223372036854775807,\"str\":{\"pre\":0,\"str\":\"a\"}}\n{\"in\":2,\"str\":{\"pre\":9223372036854775807,\"str\":\"b\"}}\n");
        // ids are arbitrary naturals: 2^63 and above are ids like any other
        c("id 2^63", "ok", "{\"in\":" + TWO63 + ",\"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        c("huge id", "ok", "{\"in\":" + BIG + ",\"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        c("huge level id", "ok", "{\"il\":" + BIG + ",\"succ\":0}\n");
        c("huge expr id", "ok", "{\"bvar\":0,\"ie\":" + BIG + "}\n");
        c("reference to a huge undefined id", "M2", "{\"in\":1,\"str\":{\"pre\":" + BIG + ",\"str\":\"a\"}}\n");
        c("huge id with an undefined reference", "M2", "{\"in\":" + BIG + ",\"str\":{\"pre\":5,\"str\":\"a\"}}\n");
        c("id written with an exponent, huge", "ok", "{\"in\":1e30,\"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        c("huge ids used: names, levels, expressions, a declaration", "ok",
                "{\"in\":" + BIG + ",\"str\":{\"pre\":0,\"str\":\"u\"}}\n"
                        + "{\"in\":" + TWO63 + ",\"str\":{\"pre\":" + BIG + ",\"str\":\"a\"}}\n"
                        + "{\"il\":" + BIG + ",\"param\":" + BIG + "}\n"
                        + "{\"il\":" + TWO63 + ",\"succ\":" + BIG + "}\n"
                        + "{\"ie\":" + BIG + ",\"sort\":" + BIG + "}\n"
                        + "{\"ie\":" + TWO63 + ",\"sort\":" + TWO63 + "}\n"
                        + "{\"ie\":1e30,\"const\":{\"name\":" + TWO63 + ",\"us\":[" + BIG + "," + TWO63 + "]}}\n"
                        + "{\"app\":{\"arg\":" + BIG + ",\"fn\":1e30},\"ie\":7}\n"
                        + "{\"axiom\":{\"isUnsafe\":false,\"levelParams\":[" + BIG + "],\"name\":" + TWO63 + ",\"type\":" + TWO63 + "}}\n");
        c("the same huge id twice", "M3", "{\"il\":" + BIG + ",\"succ\":0}\n{\"il\":" + BIG + ",\"succ\":0}\n");
        c("the same huge id twice, written differently", "M3", "{\"il\":1e30,\"succ\":0}\n{\"il\":1000000000000000000000000000000,\"succ\":0}\n");
        c("two huge ids that differ in the last digit", "ok", "{\"il\":" + BIG + ",\"succ\":0}\n{\"il\":" + BIG.substring(0, BIG.length() - 1) + "1,\"succ\":" + BIG + "}\n");
        c("a huge id is not the id of another space", "M4", "{\"il\":" + BIG + ",\"succ\":0}\n{\"bvar\":0,\"ie\":1}\n{\"app\":{\"arg\":1,\"fn\":" + BIG + "},\"ie\":2}\n");
        c("one huge number as a name id, a level id and an expression id", "ok",
                "{\"in\":" + BIG + ",\"str\":{\"pre\":0,\"str\":\"a\"}}\n{\"il\":" + BIG + ",\"param\":" + BIG + "}\n{\"ie\":" + BIG + ",\"sort\":" + BIG + "}\n");
        // ---------------------------------------------------------------- names
        c("name str without pre", "M2", "{\"in\":1,\"str\":{\"str\":\"a\"}}\n");
        c("name str without str", "M2", "{\"in\":1,\"str\":{\"pre\":0}}\n");
        c("name str: pre is a string", "M2", "{\"in\":1,\"str\":{\"pre\":\"0\",\"str\":\"a\"}}\n");
        c("name str: str is a number", "M2", "{\"in\":1,\"str\":{\"pre\":0,\"str\":5}}\n");
        c("name str: data is a string", "M2", "{\"in\":1,\"str\":\"a\"}\n");
        c("name num without i", "M2", "{\"in\":1,\"num\":{\"pre\":0}}\n");
        c("name num: i is a string", "M2", "{\"in\":1,\"num\":{\"i\":\"1\",\"pre\":0}}\n");
        c("name num: i negative", "M2", "{\"in\":1,\"num\":{\"i\":-1,\"pre\":0}}\n");
        c("name num: i fractional", "M2", "{\"in\":1,\"num\":{\"i\":1.5,\"pre\":0}}\n");
        c("name num: i huge", "ok", "{\"in\":1,\"num\":{\"i\":" + BIG + ",\"pre\":0}}\n{\"in\":2,\"num\":{\"i\":1e40,\"pre\":1}}\n");
        c("name num: i with 16 digits", "ok", "{\"in\":1,\"num\":{\"i\":1234567890123456,\"pre\":0}}\n{\"in\":2,\"num\":{\"i\":999999999999999,\"pre\":1}}\n");
        c("name with both str and num", "M2", "{\"in\":1,\"num\":{\"i\":1,\"pre\":0},\"str\":{\"pre\":0,\"str\":\"a\"}}\n");
        // ---------------------------------------------------------------- levels
        c("all level kinds", "ok", N1 + "{\"il\":1,\"succ\":0}\n{\"il\":2,\"max\":[1,0]}\n{\"il\":3,\"imax\":[2,1]}\n{\"il\":4,\"param\":1}\n");
        c("level keys in another order", "ok", N1 + "{\"succ\":0,\"il\":1}\n{\"max\":[1,0],\"il\":2}\n{\"imax\":[2,1],\"il\":3}\n{\"param\":1,\"il\":4}\n");
        c("succ of an undefined level", "M2", "{\"il\":1,\"succ\":3}\n");
        c("succ of itself", "M2", "{\"il\":1,\"succ\":1}\n");
        c("succ of a string", "M2", "{\"il\":1,\"succ\":\"0\"}\n");
        c("max with three operands", "M2", "{\"il\":1,\"max\":[0,0,0]}\n");
        c("max with one operand", "M2", "{\"il\":1,\"max\":[0]}\n");
        c("max of a number", "M2", "{\"il\":1,\"max\":0}\n");
        c("imax with a string operand", "M2", "{\"il\":1,\"imax\":[0,\"0\"]}\n");
        c("imax of an object", "M2", "{\"il\":1,\"imax\":{\"0\":0}}\n");
        c("param of an undefined name", "M2", "{\"il\":1,\"param\":1}\n");
        c("param of the anonymous name", "ok", "{\"il\":1,\"param\":0}\n");
        c("unknown level kind", "M2", "{\"il\":1,\"zero\":0}\n");
        c("level kind under the wrong id key", "M2", "{\"ie\":1,\"succ\":0}\n");
        // ---------------------------------------------------------------- expressions
        c("largest bound variable index", "ok", "{\"bvar\":1048574,\"ie\":0}\n");
        c("bound variable index 2^20-1", "M2", "{\"bvar\":1048575,\"ie\":0}\n");
        c("huge bound variable index", "M2", "{\"bvar\":" + BIG + ",\"ie\":0}\n");
        c("bvar of a string", "M2", "{\"bvar\":\"0\",\"ie\":0}\n");
        c("sort of an undefined level", "M2", "{\"ie\":0,\"sort\":1}\n");
        c("sort of an object", "M2", "{\"ie\":0,\"sort\":{}}\n");
        c("const without us", "M3", N1 + "{\"const\":{\"name\":1},\"ie\":0}\n");
        c("const: us is not an array", "M3", N1 + "{\"const\":{\"name\":1,\"us\":0},\"ie\":0}\n");
        c("const: us holds a string", "M3", N1 + "{\"const\":{\"name\":1,\"us\":[0,\"x\"]},\"ie\":0}\n");
        c("const: undefined level", "M3", N1 + "{\"const\":{\"name\":1,\"us\":[0,7]},\"ie\":0}\n");
        c("const: undefined name", "M2", "{\"const\":{\"name\":1,\"us\":[]},\"ie\":0}\n");
        c("const: many levels", "ok", N1 + "{\"const\":{\"name\":1,\"us\":[" + "0,".repeat(200) + "0]},\"ie\":0}\n");
        c("const: whitespace in the level list", "ok", N1 + "{\"const\":{\"name\":1,\"us\":[0, 0]},\"ie\":0}\n");
        c("const: empty element in the level list", "M3", N1 + "{\"const\":{\"name\":1,\"us\":[0,,0]},\"ie\":0}\n");
        c("app without fn", "M3", "{\"bvar\":0,\"ie\":0}\n{\"app\":{\"arg\":0},\"ie\":1}\n");
        c("app: fn is a string", "M3", "{\"bvar\":0,\"ie\":0}\n{\"app\":{\"arg\":0,\"fn\":\"0\"},\"ie\":1}\n");
        c("app of a number", "M3", "{\"bvar\":0,\"ie\":0}\n{\"app\":0,\"ie\":1}\n");
        c("app: undefined argument", "M3", "{\"bvar\":0,\"ie\":0}\n{\"app\":{\"arg\":9,\"fn\":0},\"ie\":1}\n");
        c("app: ten digit argument", "M3", "{\"bvar\":0,\"ie\":0}\n{\"app\":{\"arg\":1234567890,\"fn\":0},\"ie\":1}\n");
        c("app: id is not the next one", "ok", "{\"bvar\":0,\"ie\":0}\n{\"app\":{\"arg\":0,\"fn\":0},\"ie\":5}\n{\"app\":{\"arg\":5,\"fn\":0},\"ie\":1}\n");
        c("lam: unknown binder info", "M3", "{\"bvar\":0,\"ie\":0}\n{\"ie\":1,\"lam\":{\"binderInfo\":\"Default\",\"body\":0,\"name\":0,\"type\":0}}\n");
        c("lam: binder info with a tail", "M3", "{\"bvar\":0,\"ie\":0}\n{\"ie\":1,\"lam\":{\"binderInfo\":\"defaultX\",\"body\":0,\"name\":0,\"type\":0}}\n");
        c("lam: binder info is a number", "M3", "{\"bvar\":0,\"ie\":0}\n{\"ie\":1,\"lam\":{\"binderInfo\":0,\"body\":0,\"name\":0,\"type\":0}}\n");
        c("lam without body", "M3", "{\"bvar\":0,\"ie\":0}\n{\"ie\":1,\"lam\":{\"binderInfo\":\"default\",\"name\":0,\"type\":0}}\n");
        c("lam: undefined binder name", "M3", "{\"bvar\":0,\"ie\":0}\n{\"ie\":1,\"lam\":{\"binderInfo\":\"default\",\"body\":0,\"name\":4,\"type\":0}}\n");
        c("forallE: undefined type", "M3", "{\"bvar\":0,\"ie\":0}\n{\"forallE\":{\"binderInfo\":\"default\",\"body\":0,\"name\":0,\"type\":3},\"ie\":1}\n");
        c("forallE: escaped binder info", "ok", "{\"bvar\":0,\"ie\":0}\n{\"forallE\":{\"binderInfo\":\"inst\\u0049mplicit\",\"body\":0,\"name\":0,\"type\":0},\"ie\":1}\n");
        c("letE without nondep", "M3", "{\"bvar\":0,\"ie\":0}\n{\"ie\":1,\"letE\":{\"body\":0,\"name\":0,\"type\":0,\"value\":0}}\n");
        c("letE: nondep is a string", "M3", "{\"bvar\":0,\"ie\":0}\n{\"ie\":1,\"letE\":{\"body\":0,\"name\":0,\"nondep\":\"false\",\"type\":0,\"value\":0}}\n");
        c("letE: nondep null", "M3", "{\"bvar\":0,\"ie\":0}\n{\"ie\":1,\"letE\":{\"body\":0,\"name\":0,\"nondep\":null,\"type\":0,\"value\":0}}\n");
        c("letE: undefined value", "M3", "{\"bvar\":0,\"ie\":0}\n{\"ie\":1,\"letE\":{\"body\":0,\"name\":0,\"nondep\":true,\"type\":0,\"value\":2}}\n");
        c("proj: undefined struct", "M3", N1 + "{\"ie\":0,\"proj\":{\"idx\":0,\"struct\":0,\"typeName\":1}}\n");
        c("proj: undefined type name", "M3", "{\"bvar\":0,\"ie\":0}\n{\"ie\":1,\"proj\":{\"idx\":0,\"struct\":0,\"typeName\":1}}\n");
        c("proj: large index", "ok", "{\"bvar\":0,\"ie\":0}\n{\"ie\":1,\"proj\":{\"idx\":9223372036854775807,\"struct\":0,\"typeName\":0}}\n{\"ie\":2,\"proj\":{\"idx\":1234567890,\"struct\":1,\"typeName\":0}}\n");
        c("proj: index 2^63", "Ua3", "{\"bvar\":0,\"ie\":0}\n{\"ie\":1,\"proj\":{\"idx\":" + TWO63 + ",\"struct\":0,\"typeName\":0}}\n");
        c("proj: index 2^63 and the id is bound twice", "M3", "{\"bvar\":0,\"ie\":0}\n{\"ie\":0,\"proj\":{\"idx\":" + TWO63 + ",\"struct\":0,\"typeName\":0}}\n");
        c("proj: index is a string", "M3", "{\"bvar\":0,\"ie\":0}\n{\"ie\":1,\"proj\":{\"idx\":\"0\",\"struct\":0,\"typeName\":0}}\n");
        c("natVal forms", "ok", "{\"ie\":0,\"natVal\":\"007\"}\n{\"ie\":1,\"natVal\":\"1_000\"}\n{\"ie\":2,\"natVal\":\"0\"}\n{\"ie\":3,\"natVal\":\"" + "9".repeat(5000) + "\"}\n");
        c("natVal empty", "M2", "{\"ie\":0,\"natVal\":\"\"}\n");
        c("natVal with two underscores", "M2", "{\"ie\":0,\"natVal\":\"1__0\"}\n");
        c("natVal starting with an underscore", "M2", "{\"ie\":0,\"natVal\":\"_1\"}\n");
        c("natVal ending with an underscore", "M2", "{\"ie\":0,\"natVal\":\"1_\"}\n");
        c("natVal as a number", "M2", "{\"ie\":0,\"natVal\":5}\n");
        c("natVal with a letter", "M2", "{\"ie\":0,\"natVal\":\"12a\"}\n");
        c("natVal with a space", "M2", "{\"ie\":0,\"natVal\":\" 12\"}\n");
        c("natVal with a sign", "M2", "{\"ie\":0,\"natVal\":\"+12\"}\n");
        c("natVal with non-ASCII digits", "M2", "{\"ie\":0,\"natVal\":\"\u00d9\u00a1\"}\n");
        c("natVal with an escaped digit", "ok", "{\"ie\":0,\"natVal\":\"\\u0031\"}\n");
        c("natVal hexadecimal", "M2", "{\"ie\":0,\"natVal\":\"0xff\"}\n");
        c("strVal as a number", "M2", "{\"ie\":0,\"strVal\":5}\n");
        c("strVal null", "M2", "{\"ie\":0,\"strVal\":null}\n");
        c("strVal forms", "ok", "{\"ie\":0,\"strVal\":\"\"}\n{\"ie\":1,\"strVal\":\"a\\nb\\\"c\\\\\"}\n{\"ie\":2,\"strVal\":\"\u00c3\u00a9\\u00e9\"}\n");
        c("mdata", "ok", "{\"bvar\":0,\"ie\":0}\n{\"ie\":1,\"mdata\":{\"data\":{\"a\":[1,2,{\"b\":null}]},\"expr\":0}}\n{\"mdata\":{\"expr\":1,\"data\":{}},\"ie\":2}\n");
        c("mdata: data is an array", "M3", "{\"bvar\":0,\"ie\":0}\n{\"ie\":1,\"mdata\":{\"data\":[],\"expr\":0}}\n");
        c("mdata without data", "M3", "{\"bvar\":0,\"ie\":0}\n{\"ie\":1,\"mdata\":{\"expr\":0}}\n");
        c("mdata: undefined expr", "M3", "{\"bvar\":0,\"ie\":0}\n{\"ie\":1,\"mdata\":{\"data\":{},\"expr\":1}}\n");
        c("unknown expression kind", "M2", "{\"fvar\":0,\"ie\":0}\n");
        c("expression kind under the wrong id key", "M2", "{\"bvar\":0,\"il\":1}\n");
        c("expression keys in another order", "ok", N1
                + "{\"ie\":0,\"bvar\":0}\n"
                + "{\"ie\":1,\"app\":{\"fn\":0,\"arg\":0}}\n"
                + "{\"lam\":{\"type\":0,\"name\":1,\"body\":1,\"binderInfo\":\"implicit\"},\"ie\":2}\n"
                + "{\"ie\":3,\"forallE\":{\"type\":0,\"name\":1,\"body\":1,\"binderInfo\":\"strictImplicit\"}}\n"
                + "{\"letE\":{\"value\":0,\"type\":0,\"nondep\":true,\"name\":1,\"body\":1},\"ie\":4}\n"
                + "{\"proj\":{\"typeName\":1,\"struct\":0,\"idx\":2},\"ie\":5}\n"
                + "{\"sort\":0,\"ie\":6}\n"
                + "{\"ie\":7,\"const\":{\"us\":[0],\"name\":1}}\n"
                + "{\"natVal\":\"5\",\"ie\":8}\n"
                + "{\"strVal\":\"s\",\"ie\":9}\n");
        // ---------------------------------------------------------------- declarations
        c("axiom", "ok", PRE + "{\"axiom\":{\"isUnsafe\":false,\"levelParams\":[],\"name\":1,\"type\":1}}\n");
        c("axiom without isUnsafe", "M7", PRE + "{\"axiom\":{\"levelParams\":[],\"name\":1,\"type\":1}}\n");
        c("axiom: isUnsafe is a number", "M7", PRE + "{\"axiom\":{\"isUnsafe\":0,\"levelParams\":[],\"name\":1,\"type\":1}}\n");
        c("axiom: levelParams holds a string", "M7", PRE + "{\"axiom\":{\"isUnsafe\":false,\"levelParams\":[\"u\"],\"name\":1,\"type\":1}}\n");
        c("axiom: undefined level parameter name", "M7", PRE + "{\"axiom\":{\"isUnsafe\":false,\"levelParams\":[9],\"name\":1,\"type\":1}}\n");
        c("axiom: undefined type", "M7", PRE + "{\"axiom\":{\"isUnsafe\":false,\"levelParams\":[],\"name\":1,\"type\":9}}\n");
        c("axiom: undefined name", "M7", PRE + "{\"axiom\":{\"isUnsafe\":false,\"levelParams\":[],\"name\":9,\"type\":1}}\n");
        c("axiom is an array", "M7", PRE + "{\"axiom\":[]}\n");
        c("axiom with an extra top-level key", "M7", PRE + "{\"axiom\":{\"isUnsafe\":false,\"levelParams\":[],\"name\":1,\"type\":1},\"x\":1}\n");
        c("axiom with an extra nested key", "ok", PRE + "{\"axiom\":{\"isUnsafe\":false,\"levelParams\":[],\"name\":1,\"type\":1,\"x\":1}}\n");
        c("definitions", "ok", PRE
                + "{\"def\":{\"all\":[1],\"hints\":\"abbrev\",\"levelParams\":[],\"name\":1,\"safety\":\"safe\",\"type\":0,\"value\":1}}\n"
                + "{\"def\":{\"all\":[2],\"hints\":{\"regular\":4294967297,\"x\":[1,{\"a\":null}]},\"levelParams\":[],\"name\":2,\"safety\":\"safe\",\"type\":0,\"value\":1}}\n");
        c("def: opaque hint and other key order", "ok", PRE + "{\"def\":{\"value\":1,\"type\":0,\"safety\":\"safe\",\"name\":1,\"levelParams\":[],\"hints\":\"opaque\",\"all\":[1]}}\n");
        c("def: unknown hint", "M7", PRE + "{\"def\":{\"all\":[1],\"hints\":\"foo\",\"levelParams\":[],\"name\":1,\"safety\":\"safe\",\"type\":0,\"value\":1}}\n");
        c("def: hint is a number", "M7", PRE + "{\"def\":{\"all\":[1],\"hints\":1,\"levelParams\":[],\"name\":1,\"safety\":\"safe\",\"type\":0,\"value\":1}}\n");
        c("def: hint object without regular", "M7", PRE + "{\"def\":{\"all\":[1],\"hints\":{},\"levelParams\":[],\"name\":1,\"safety\":\"safe\",\"type\":0,\"value\":1}}\n");
        c("def: regular is a string", "M7", PRE + "{\"def\":{\"all\":[1],\"hints\":{\"regular\":\"1\"},\"levelParams\":[],\"name\":1,\"safety\":\"safe\",\"type\":0,\"value\":1}}\n");
        c("def: huge regular height", "ok", PRE + "{\"def\":{\"all\":[1],\"hints\":{\"regular\":" + BIG + "},\"levelParams\":[],\"name\":1,\"safety\":\"safe\",\"type\":0,\"value\":1}}\n");
        c("def without hints", "M7", PRE + "{\"def\":{\"all\":[1],\"levelParams\":[],\"name\":1,\"safety\":\"safe\",\"type\":0,\"value\":1}}\n");
        c("def: unknown safety", "M7", PRE + "{\"def\":{\"all\":[1],\"hints\":\"abbrev\",\"levelParams\":[],\"name\":1,\"safety\":\"Safe\",\"type\":0,\"value\":1}}\n");
        c("def: safety with a tail", "M7", PRE + "{\"def\":{\"all\":[1],\"hints\":\"abbrev\",\"levelParams\":[],\"name\":1,\"safety\":\"safely\",\"type\":0,\"value\":1}}\n");
        c("def without all", "M7", PRE + "{\"def\":{\"hints\":\"abbrev\",\"levelParams\":[],\"name\":1,\"safety\":\"safe\",\"type\":0,\"value\":1}}\n");
        c("def: undefined name in all", "M7", PRE + "{\"def\":{\"all\":[1,9],\"hints\":\"abbrev\",\"levelParams\":[],\"name\":1,\"safety\":\"safe\",\"type\":0,\"value\":1}}\n");
        c("def: undefined value", "M7", PRE + "{\"def\":{\"all\":[1],\"hints\":\"abbrev\",\"levelParams\":[],\"name\":1,\"safety\":\"safe\",\"type\":0,\"value\":7}}\n");
        c("def: empty element in a list", "M7", PRE + "{\"def\":{\"all\":[1,],\"hints\":\"abbrev\",\"levelParams\":[],\"name\":1,\"safety\":\"safe\",\"type\":0,\"value\":1}}\n");
        c("def: unsafe and partial", "ok", PRE
                + "{\"def\":{\"all\":[1],\"hints\":\"abbrev\",\"levelParams\":[],\"name\":1,\"safety\":\"unsafe\",\"type\":0,\"value\":1}}\n"
                + "{\"def\":{\"all\":[2],\"hints\":\"abbrev\",\"levelParams\":[],\"name\":2,\"safety\":\"partial\",\"type\":0,\"value\":1}}\n");
        c("opaque", "ok", PRE + "{\"opaque\":{\"all\":[1],\"isUnsafe\":false,\"levelParams\":[],\"name\":1,\"type\":0,\"value\":1}}\n");
        c("opaque without isUnsafe", "ok", PRE + "{\"opaque\":{\"all\":[1],\"levelParams\":[],\"name\":1,\"type\":0,\"value\":1}}\n");
        c("opaque: isUnsafe null", "M7", PRE + "{\"opaque\":{\"all\":[1],\"isUnsafe\":null,\"levelParams\":[],\"name\":1,\"type\":0,\"value\":1}}\n");
        c("opaque without value", "M7", PRE + "{\"opaque\":{\"all\":[1],\"isUnsafe\":false,\"levelParams\":[],\"name\":1,\"type\":0}}\n");
        c("theorem", "ok", "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"pImpliesP\"}}\n{\"in\":2,\"str\":{\"pre\":0,\"str\":\"p\"}}\n{\"ie\":0,\"sort\":0}\n"
                + "{\"in\":3,\"str\":{\"pre\":0,\"str\":\"h\"}}\n{\"bvar\":0,\"ie\":1}\n{\"bvar\":1,\"ie\":2}\n"
                + "{\"forallE\":{\"binderInfo\":\"default\",\"body\":2,\"name\":3,\"type\":1},\"ie\":3}\n"
                + "{\"forallE\":{\"binderInfo\":\"default\",\"body\":3,\"name\":2,\"type\":0},\"ie\":4}\n"
                + "{\"ie\":5,\"lam\":{\"binderInfo\":\"default\",\"body\":1,\"name\":3,\"type\":1}}\n"
                + "{\"ie\":6,\"lam\":{\"binderInfo\":\"default\",\"body\":5,\"name\":2,\"type\":0}}\n"
                + "{\"thm\":{\"all\":[1],\"levelParams\":[],\"name\":1,\"type\":4,\"value\":6}}\n");
        c("thm without value", "M7", PRE + "{\"thm\":{\"all\":[1],\"levelParams\":[],\"name\":1,\"type\":1}}\n");
        c("thm: value is a string", "M7", PRE + "{\"thm\":{\"all\":[1],\"levelParams\":[],\"name\":1,\"type\":1,\"value\":\"1\"}}\n");
        c("thm: all is a number", "M7", PRE + "{\"thm\":{\"all\":1,\"levelParams\":[],\"name\":1,\"type\":1,\"value\":1}}\n");
        c("thm: undefined value", "M7", PRE + "{\"thm\":{\"all\":[1],\"levelParams\":[],\"name\":1,\"type\":1,\"value\":8}}\n");
        c("thm is a number", "M7", PRE + "{\"thm\":5}\n");
        c("thm: space in a list", "M7", PRE + "{\"thm\":{\"all\":[1 ],\"levelParams\":[],\"name\":1,\"type\":1,\"value\":9}}\n");
        c("quot: unknown kind", "M7", PRE + "{\"quot\":{\"kind\":\"mk\",\"levelParams\":[],\"name\":1,\"type\":0}}\n");
        c("quot without kind", "M7", PRE + "{\"quot\":{\"levelParams\":[],\"name\":1,\"type\":0}}\n");
        c("quot: kind is a number", "M7", PRE + "{\"quot\":{\"kind\":0,\"levelParams\":[],\"name\":1,\"type\":0}}\n");
        c("inductive block", "ok", ind("[" + CTOR_F + "," + CTOR_T + "]", "[" + REC + "]", "[" + TYPE + "]"));
        c("inductive block without recursors", "ok", ind("[" + CTOR_F + "," + CTOR_T + "]", "[]", "[" + TYPE + "]"));
        c("inductive: keys in another order", "ok", BOOL_PRE + "{\"inductive\":{\"types\":[" + TYPE + "],\"recs\":[" + REC + "],\"ctors\":[" + CTOR_F + "," + CTOR_T + "]}}\n");
        c("inductive without types", "M35", BOOL_PRE + "{\"inductive\":{\"ctors\":[],\"recs\":[]}}\n");
        c("inductive without ctors", "M35", BOOL_PRE + "{\"inductive\":{\"recs\":[],\"types\":[]}}\n");
        c("inductive without recs", "M35", BOOL_PRE + "{\"inductive\":{\"ctors\":[],\"types\":[]}}\n");
        c("inductive: types is an object", "M35", BOOL_PRE + "{\"inductive\":{\"ctors\":[],\"recs\":[],\"types\":{}}}\n");
        c("inductive: a type is a number", "M35", ind("[]", "[]", "[1]"));
        c("inductive: a ctor is a string", "M35", ind("[\"x\"]", "[]", "[" + TYPE + "]"));
        c("inductive: a rec is an array", "M35", ind("[]", "[[]]", "[" + TYPE + "]"));
        c("inductive: type without isRec", "M35", ind("[]", "[]", "[" + TYPE.replace("\"isRec\":false,", "") + "]"));
        c("inductive: type without numNested", "M35", ind("[]", "[]", "[" + TYPE.replace("\"numNested\":0,", "") + "]"));
        c("inductive: type with isReflexive 1", "M35", ind("[]", "[]", "[" + TYPE.replace("\"isReflexive\":false", "\"isReflexive\":1") + "]"));
        c("inductive: type with an undefined ctor name", "M35", ind("[]", "[]", "[" + TYPE.replace("\"ctors\":[2,3]", "\"ctors\":[2,33]") + "]"));
        c("inductive: ctor without cidx", "M35", ind("[" + CTOR_F.replace("\"cidx\":0,", "") + "]", "[]", "[" + TYPE + "]"));
        c("inductive: ctor with an undefined induct", "M35", ind("[" + CTOR_F.replace("\"induct\":1", "\"induct\":77") + "]", "[]", "[" + TYPE + "]"));
        c("inductive: ctor with a fractional numFields", "M35", ind("[" + CTOR_F.replace("\"numFields\":0", "\"numFields\":0.5") + "]", "[]", "[" + TYPE + "]"));
        c("inductive: rec without k", "M35", ind("[]", "[" + REC.replace("\"k\":false,", "") + "]", "[" + TYPE + "]"));
        c("inductive: rec without rules", "M35", ind("[]", "[" + REC.replace("\"rules\":" + RULES + ",", "") + "]", "[" + TYPE + "]"));
        c("inductive: rule is a number", "M35", ind("[]", "[" + REC.replace(RULES, "[1]") + "]", "[" + TYPE + "]"));
        c("inductive: rule without nfields", "M35", ind("[]", "[" + REC.replace("\"nfields\":0,\"rhs\":18", "\"rhs\":18") + "]", "[" + TYPE + "]"));
        c("inductive: rule with an undefined rhs", "M35", ind("[]", "[" + REC.replace("\"rhs\":21", "\"rhs\":99") + "]", "[" + TYPE + "]"));
        c("inductive: rule with an undefined ctor", "M35", ind("[]", "[" + REC.replace("\"ctor\":3", "\"ctor\":99") + "]", "[" + TYPE + "]"));
        c("inductive: huge numParams", "Ua35", ind("[" + CTOR_F + "," + CTOR_T + "]", "[" + REC + "]", "[" + TYPE.replace("\"numParams\":0", "\"numParams\":" + TWO63) + "]"));
        c("inductive: huge nfields", "Ua35", ind("[" + CTOR_F + "," + CTOR_T + "]", "[" + REC.replace("\"nfields\":0,\"rhs\":21", "\"nfields\":" + BIG + ",\"rhs\":21") + "]", "[" + TYPE + "]"));
        c("inductive: large counts", "ok", ind("[" + CTOR_F.replace("\"numFields\":0", "\"numFields\":9223372036854775807") + "," + CTOR_T + "]", "[" + REC + "]", "[" + TYPE.replace("\"numIndices\":0", "\"numIndices\":4294967296") + "]"));
        c("inductive with an extra top-level key", "M35", BOOL_PRE + "{\"inductive\":{\"ctors\":[],\"recs\":[],\"types\":[]},\"x\":0}\n");
        c("inductive with extra nested keys", "ok", BOOL_PRE + "{\"inductive\":{\"ctors\":[],\"recs\":[],\"types\":[],\"x\":0}}\n");
    }

    private static String wideKeys(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "" : ",").append("\"k").append(i).append("\":").append(i);
        }
        return sb.toString();
    }

    private static ExportException.Kind kindOf(String expected) {
        return expected.startsWith("M") ? ExportException.Kind.MALFORMED : ExportException.Kind.UNSUPPORTED;
    }

    private static long lineOf(String expected) {
        return Long.parseLong(expected.replaceAll("[^0-9]", ""));
    }

    @Test
    void allReadersAgreeWithTheTable() {
        assertTrue(CASES.size() > 250);
        for (Case c : CASES) {
            T.Run r = T.agree(c.name, c.file);
            if (c.expected.equals("ok")) {
                if (!r.ok) {
                    fail(c.name + ": expected ok but " + r);
                }
            } else {
                if (r.ok) {
                    fail(c.name + ": expected " + c.expected + " but the file was read");
                }
                assertEquals(kindOf(c.expected) + "@" + lineOf(c.expected), r.outcome(), c.name + ": " + r);
            }
        }
    }

    @Test
    void columnarStoreReadsWhatTheTraceSinkReads() throws IOException {
        // the reference store must accept the same files (none of the cases declares a name twice)
        for (Case c : CASES) {
            String outcome;
            try {
                ExportReader.read(c.file, new ColumnarStore());
                outcome = "ok";
            } catch (ExportException e) {
                outcome = (e.kind() == ExportException.Kind.MALFORMED ? "M" : "U") + e.line();
            }
            assertEquals(c.expected.replaceAll("[ar?]", ""), outcome, c.name);
        }
    }

    /** Whether the official reader parsed the file (whatever the kernel then said about it). */
    private static boolean officialParses(Path dir, int index, Case c) throws IOException, InterruptedException {
        Path f = dir.resolve("case" + index + ".ndjson");
        Files.write(f, c.file);
        Process p = new ProcessBuilder(T.LEANCHECKER.toString(), "--from-export", f.toString())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail(c.name + ": leanchecker timed out");
        }
        Files.delete(f);
        if (out.contains("Lean default kernel")) {
            return true;
        }
        if (out.contains("uncaught exception") || out.contains("INTERNAL PANIC")) {
            return false;
        }
        fail(c.name + ": unexpected leanchecker output (exit " + p.exitValue() + "): " + out);
        return false;
    }

    @Test
    void theOfficialReaderAgreesWithTheTable() throws Exception {
        assumeTrue(Files.isExecutable(T.LEANCHECKER), "official leanchecker 4.36.0 not installed");
        Path dir = Files.createTempDirectory("rlvm-export-edge");
        try {
            List<String> wrong = new ArrayList<>();
            for (int i = 0; i < CASES.size(); i++) {
                Case c = CASES.get(i);
                if (c.expected.startsWith("U?")) {
                    continue;
                }
                boolean parses = officialParses(dir, i, c);
                boolean expectParses = c.expected.equals("ok") || c.expected.startsWith("Ua");
                if (parses != expectParses) {
                    wrong.add(c.name + ": table says " + c.expected + ", official reader "
                            + (parses ? "parses the file" : "fails"));
                }
            }
            assertTrue(wrong.isEmpty(), String.join("\n", wrong));
        } finally {
            Files.deleteIfExists(dir);
        }
    }
}
