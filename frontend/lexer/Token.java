package frontend.lexer;


import manager.Manager;

import java.util.regex.Pattern;

public class Token {
    public Type type;
    public String content;
    public int line;

    public Token(Type type, String content) {
        // 功能用token
        this.type = type;
        this.content = content;
        this.line = Manager.BufferReader.line;
    }

    public static boolean isReservedWord(String string) {
        for (Type type : Type.values()) {
            if (type.getPattern().matcher(string).matches()) {
                return type.reserved;
            }
        }
        return false;
    }

    public enum Type {
        INT("int", true),
        FLOAT("float", true),
        VOID("void", true),
        RETURN("return", true),
        CONST("const", true),
        IF("if", true),
        WHILE("while", true),
        FOR("for", true),
        BREAK("break", true),
        CONTINUE("continue", true),
        ELSE("else", true),
        IDENTIFIER("[A-Za-z_][A-Za-z0-9_]*"),
        L_PAREN("\\("),
        R_PAREN("\\)"),
        L_BRACE("\\{"),
        R_BRACE("\\}"),
        L_BRACK("\\["),
        R_BRACK("]"),
        LOR("\\|\\|"),
        LAND("&&"),
        EQ("=="),
        NE("!="),
        LE("<="),
        GE(">="),
        LT("<"),
        GT(">"),
        ADD("\\+"),
        SUB("-"),
        MUL("\\*"),
        DIV("/"),
        MOD("%"),
        NOT("!"),
        ASSIGN("="),
        COMMA(","),
        DEC_FLOAT("([0-9]*\\.[0-9]*((p|P|e|E)(\\+|\\-)?[0-9]+)?)|" +
                "([0-9]*[\\.]?[0-9]*(p|P|e|E)((\\+|\\-)?[0-9]+)?)"),
        HEX_FLOAT("(0(x|X)[0-9A-Fa-f]*\\.[0-9A-Fa-f]*((p|P|e|E)(\\+|\\-)?[0-9A-Fa-f]*)?)|" +
                "(0(x|X)[0-9A-Fa-f]*[\\.]?[0-9A-Fa-f]*(p|P|e|E)((\\+|\\-)?[0-9A-Fa-f]*)?)"),
        DEC_INT("0|([1-9][0-9]*)"),
        HEX_INT("0(x|X)[0-9A-Fa-f]+"),
        OCT_INT("0[0-7]+"),
        SEMI(";"),
        STR("\"[^\"]*\""),
        EOF("");


        private final String content;
        private final boolean reserved;

        Type(String content) {
            this.content = content;
            this.reserved = false;
        }

        Type(String content, boolean reserved) {
            this.content = content;
            this.reserved = reserved;
        }

        public String getContent() {
            return content;
        }

        public Pattern getPattern() {
            return Pattern.compile("^(" + content + ")" + (reserved ? "(?!\\w)" : ""));
        }
    }
}
