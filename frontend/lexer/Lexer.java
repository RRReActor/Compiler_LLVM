package frontend.lexer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.regex.Pattern;


public class Lexer {
    private TokenArray tokenArray;
    private BufferedInputStream src;
    private BufferReader BuffReader;

    private boolean debugMode;

    public Lexer(BufferedInputStream src, TokenArray tokenArray) {
        this.src = src;
        this.tokenArray = tokenArray;
        this.BuffReader = new BufferReader(src);
        this.debugMode = false;
    }

    public int addToken(String str) {
        for (TokenType tokenType : TokenType.values()) {
            Pattern p = tokenType.getPattern();
            if (p.matcher(str).matches()) {
                if (debugMode) {
                    System.out.println(tokenType + "\t" + str);
                }
                tokenArray.append(new Token(tokenType, str, BufferReader.line));
                return 0;
            }
        }
        return -1;
    }

    public int replaceToken(String str) {
        for (TokenType tokenType : TokenType.values()) {
            Pattern p = tokenType.getPattern();
            if (p.matcher(str).matches()) {
                if (debugMode) {
                    System.out.println(tokenType + "\t" + str);
                }
                tokenArray.setToken(tokenArray.index, new Token(tokenType, str, BufferReader.line));
                return 0;
            }
        }
        return -1;
    }

    public void lex() throws IOException {
        //
        StringBuilder builder = new StringBuilder();
        char last = 0;
        boolean isStr = false;
        while (!BuffReader.reachEOF()) {
            char cur = (char) BuffReader.getChar();
            switch (cur) {
                case '(' -> {
                    if (isStr) {
                        builder.append(cur);
                        break;
                    }
                    if (CharType.isIdent(last)) {
                        addToken(builder.toString());
                        builder = new StringBuilder();
                    }
                    addToken("(");
                }
                case ')' -> {
                    if (isStr) {
                        builder.append(cur);
                        break;
                    }
                    if (CharType.isIdent(last) || last == '.') {
                        addToken(builder.toString());
                        builder = new StringBuilder();
                    }
                    addToken(")");
                }
                case '{' -> {
                    if (isStr) {
                        builder.append(cur);
                        break;
                    }
                    if (CharType.isIdent(last)) {
                        addToken(builder.toString());
                        builder = new StringBuilder();
                    }
                    addToken("{");
                }
                case '}' -> {
                    if (isStr) {
                        builder.append(cur);
                        break;
                    }
                    if (CharType.isIdent(last)) {
                        addToken(builder.toString());
                        builder = new StringBuilder();
                    }
                    addToken("}");
                }
                case ';' -> {
                    if (isStr) {
                        builder.append(cur);
                        break;
                    }
                    if (CharType.isIdent(last)) {
                        addToken(builder.toString());
                        builder = new StringBuilder();
                    }
                    addToken(";");
                }
                case '+' -> {
                    if (isStr || CharType.isSpecialNum(builder.toString())) {
                        builder.append(cur);
                        break;
                    }
                    if (CharType.isIdent(last)) {
                        addToken(builder.toString());
                        builder = new StringBuilder();
                    }
                    addToken("+");
                }
                case '-' -> {
                    if (isStr || CharType.isSpecialNum(builder.toString())) {
                        builder.append(cur);
                        break;
                    }
                    if (CharType.isIdent(last)) {
                        addToken(builder.toString());
                        builder = new StringBuilder();
                    }
                    addToken("-");
                }
                case '*' -> {
                    if (isStr) {
                        builder.append(cur);
                        break;
                    }
                    if (CharType.isIdent(last)) {
                        addToken(builder.toString());
                        builder = new StringBuilder();
                    }
                    addToken("*");
                }
                case '/' -> {
                    if (isStr) {
                        builder.append(cur);
                        break;
                    }
                    if (CharType.isIdent(last)) {
                        addToken(builder.toString());
                        builder = new StringBuilder();
                    }
                    addToken("/");
                }
                case '%' -> {
                    if (isStr) {
                        builder.append(cur);
                        break;
                    }
                    if (CharType.isIdent(last)) {
                        addToken(builder.toString());
                        builder = new StringBuilder();
                    }
                    addToken("%");
                }
                case '!' -> {
                    if (isStr) {
                        builder.append(cur);
                        break;
                    }
                    if (CharType.isIdent(last)) {
                        addToken(builder.toString());
                        builder = new StringBuilder();
                    }
                    addToken("!");
                }
                case ',' -> {
                    if (isStr) {
                        builder.append(cur);
                        break;
                    }
                    if (CharType.isIdent(last) || CharType.isNumber(last)) {
                        addToken(builder.toString());
                        builder = new StringBuilder();
                    }
                    addToken(",");
                }
                case '[' -> {
                    if (isStr) {
                        builder.append(cur);
                        break;
                    }
                    if (CharType.isIdent(last)) {
                        addToken(builder.toString());
                        builder = new StringBuilder();
                    }
                    addToken("[");
                }
                case '>' -> {
                    if (isStr) {
                        builder.append(cur);
                        break;
                    }
                    if (CharType.isIdent(last)) {
                        addToken(builder.toString());
                        builder = new StringBuilder();
                    }
                    addToken(">");
                }
                case '<' -> {
                    if (isStr) {
                        builder.append(cur);
                        break;
                    }
                    if (CharType.isIdent(last)) {
                        addToken(builder.toString());
                        builder = new StringBuilder();
                    }
                    addToken("<");
                }
                case ']' -> {
                    if (isStr) {
                        builder.append(cur);
                        break;
                    }
                    if (CharType.isIdent(last)) {
                        addToken(builder.toString());
                        builder = new StringBuilder();
                    }
                    addToken("]");
                }
                case '=' -> {
                    if (isStr) {
                        builder.append(cur);
                        break;
                    }

                    if (CharType.isIdent(last)) {
                        addToken(builder.toString());
                        builder = new StringBuilder();
                    }

                    if (last == '!')
                        replaceToken("!=");
                    else if (last == '=')
                        replaceToken("==");
                    else if (last == '<')
                        replaceToken("<=");
                    else if (last == '>')
                        replaceToken(">=");
                    else
                        addToken("=");

                }
                case '|' -> {
                    if (isStr) {
                        builder.append(cur);
                        break;
                    }
                    if (CharType.isIdent(last)) {
                        addToken(builder.toString());
                        builder = new StringBuilder();
                    }
                    if (last == '|')
                        replaceToken("||");
                    else
                        addToken("|");
                }
                case '&' -> {
                    if (isStr) {
                        builder.append(cur);
                        break;
                    }
                    if (CharType.isIdent(last)) {
                        addToken(builder.toString());
                        builder = new StringBuilder();
                    }
                    if (last == '&')
                        replaceToken("&&");
                    else
                        addToken("&");
                }
                case '"' -> {
                    if (!isStr) {
                        isStr = true;
                        builder.append(cur);
                    } else {
                        builder.append(cur);
                        addToken(builder.toString());
                        builder = new StringBuilder();
                        isStr = false;
                    }
                }
                default -> {
                    builder.append(cur);
                    if (BuffReader.reachEOF()) {
                        addToken(builder.toString());
                    }
                }
            }
            last = cur;
        }

    }

    public void printTokens() {
        for (Token token : tokenArray.tokens) {
            System.out.println(token.tokenType + "\t" + token.content);
        }
    }
}
