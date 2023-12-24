package frontend.lexer;

import exception.NumberedError;
import exception.SyntaxError;
import manager.Manager;

import java.io.IOException;


public class Lexer {
    private static Manager manager;
    private static char ch;
    private static StringBuilder token;
    private static TokenArray tokenArray;

    public static void init(TokenArray tokenArray) {
        Lexer.tokenArray = tokenArray;
        // 首次读进
        moveForward();
    }

    public static void setManager(Manager manager) {
        Lexer.manager = manager;
    }


//    public Lexer(BufferedInputStream src, TokenArray tokenArray) {
//        this.src = src;
//        this.tokenArray = tokenArray;
//        this.bufReader = new BufferReader(src);
//        this.debugMode = false;
//    }
//
//    public int addToken(String str) {
//        for (TokenType tokenType : TokenType.values()) {
//            Pattern p = tokenType.getPattern();
//            if (p.matcher(str).matches()) {
//                if (debugMode) {
//                    System.out.println(tokenType + "\t" + str);
//                }
//                tokenArray.append(new Token(tokenType, str, BufferReader.line));
//                return 0;
//            }
//        }
//        return -1;
//    }
//
//    public int replaceToken(String str) {
//        for (TokenType tokenType : TokenType.values()) {
//            Pattern p = tokenType.getPattern();
//            if (p.matcher(str).matches()) {
//                if (debugMode) {
//                    System.out.println(tokenType + "\t" + str);
//                }
//                tokenArray.setToken(tokenArray.index, new Token(tokenType, str, BufferReader.line));
//                return 0;
//            }
//        }
//        return -1;
//    }

    private static boolean isDigit() {
        return Character.isDigit(ch);
    }

    private static boolean isLetter() {
        return Character.isLetter(ch);
    }

    private static boolean isIdent() {
        return isDigit() || isLetter() || (ch == '_');
    }

    private static boolean isBlank() {
        return ch == ' ' || ch == '\r' || ch == '\n' || ch == '\t';
    }

    private static boolean reachEOF() throws IOException {
        return ch == (char) -1;
    }

    private static void clearToken() {
        token = new StringBuilder();
    }

    private static void catToken() {
        token.append(ch);
    }

    /**
     * get next char from src
     * skip blank
     */
    private static void getChar() {
        ch = (char) Manager.BufferReader.getChar();
    }

    private static void moveForward() {
        do {
            ch = (char) Manager.BufferReader.getChar();
        } while (isBlank());
    }

    private static char peekChar() {
        return Manager.BufferReader.peekChar();
    }

    private static void retract() throws IOException {
        Manager.BufferReader.retract();
    }

    /**
     * 开始读入时，ch光标在该次读入的首位置
     * @return Token
     */
    private static Token getSymbol_digit() throws IOException, SyntaxError {

        if (!isDigit()) {
            throw new SyntaxError("Invalid character: " + ch);
        }
        Token tmp;

        while (isDigit()) {
            catToken();
            getChar();
        }

        if (token.toString().matches(Token.Type.HEX_INT.getContent())) {
            tmp = new Token(Token.Type.HEX_INT, token.toString());
        } else if (token.toString().matches(Token.Type.OCT_INT.getContent())) {
            tmp = new Token(Token.Type.OCT_INT, token.toString());
        } else if (token.toString().matches(Token.Type.DEC_INT.getContent())) {
            tmp = new Token(Token.Type.DEC_INT, token.toString());
        } else {
            throw new SyntaxError("Invalid character: " + ch);
        }

        retract();
        return tmp;
    }

    public static Token getSymbol() throws IOException, SyntaxError {

        if (reachEOF()) {
            return new Token(Token.Type.EOF, "EOF");
        }
        /* 开始读入时，ch光标在该次读入的首位置
         * 读入结束时，ch光标在该次读入的末位置
         */
        clearToken();
        // 带注释的情况，递归调用
        boolean recursive = false;
        Token retSymbol = null;
        switch (ch) {
            case '"':
                catToken();
                getChar();
                while (ch != '"') {
                    catToken();
                    getChar();
                }
                catToken();
                // 检查匹配

                retSymbol = new Token(Token.Type.STR, token.toString());
                if(!Token.Type.STR.getPattern().matcher(token.toString()).matches()) {
                    //
                    NumberedError err = new NumberedError(retSymbol.line, 'a');
//                    err.println(err);
                    manager.addNumberedError(err);
                }
                break;

            case ';':
                catToken();
                retSymbol = new Token(Token.Type.SEMI, token.toString());
                break;
            case ',':
                catToken();
                retSymbol = new Token(Token.Type.COMMA, token.toString());
                break;
            case '(':
                catToken();
                retSymbol = new Token(Token.Type.L_PAREN, token.toString());
                break;
            case ')':
                catToken();
                retSymbol = new Token(Token.Type.R_PAREN, token.toString());
                break;
            case '[':
                catToken();
                retSymbol = new Token(Token.Type.L_BRACK, token.toString());
                break;
            case ']':
                catToken();
                retSymbol = new Token(Token.Type.R_BRACK, token.toString());
                break;
            case '{':
                catToken();
                retSymbol = new Token(Token.Type.L_BRACE, token.toString());
                break;
            case '}':
                catToken();
                retSymbol = new Token(Token.Type.R_BRACE, token.toString());
                break;
            case '|':
                catToken();
                if (peekChar() == '|') {
                    getChar();
                    catToken();
                    retSymbol = new Token(Token.Type.LOR, token.toString());
                } else {
                    throw new SyntaxError("Invalid character: " + ch);
                }
                break;
            case '&':
                catToken();
                if (peekChar() == '&') {
                    getChar();
                    catToken();
                    retSymbol = new Token(Token.Type.LAND, token.toString());
                } else {
                    throw new SyntaxError("Invalid character: " + ch);
                }
                break;
            case '+':
                catToken();
                retSymbol = new Token(Token.Type.ADD, token.toString());
                break;
            case '-':
                catToken();
                retSymbol = new Token(Token.Type.SUB, token.toString());
                break;
            case '*':
                catToken();
                retSymbol = new Token(Token.Type.MUL, token.toString());
                break;
            case '/':
                if (peekChar() == '*') {
                    // 处理多行注释
                    getChar();
                    getChar();
                    while (true) {
                        if (ch == '*') {
                            getChar();
                            if (ch == '/') {
                                getChar();
                                break;
                            }
                        } else {
                            getChar();
                        }
                    }
                    recursive = true;
                } else if (peekChar() == '/') {
                    // 处理单行注释
                    getChar();
                    getChar();
                    while (ch != '\n') {
                        getChar();
                    }
                    recursive = true;
                } else {
                    // 作为 DIV 处理
                    catToken();
                    retSymbol = new Token(Token.Type.DIV, token.toString());
                }
                // 不做token 处理，标记递归调用
                break;
            case '%':
                catToken();
                retSymbol = new Token(Token.Type.MOD, token.toString());
                break;
            case '=':
                catToken();
                if (peekChar() == '=') {
                    getChar();
                    catToken();
                    retSymbol = new Token(Token.Type.EQ, token.toString());
                } else {
                    retSymbol = new Token(Token.Type.ASSIGN, token.toString());
                }
                break;
            case '!':
                catToken();
                if (peekChar() == '=') {
                    getChar();
                    catToken();
                    retSymbol = new Token(Token.Type.NE, token.toString());
                } else {
                    retSymbol = new Token(Token.Type.NOT, token.toString());
                }
                break;
            case '>':
                catToken();
                if (peekChar() == '=') {
                    getChar();
                    catToken();
                    retSymbol = new Token(Token.Type.GE, token.toString());
                } else {
                    retSymbol = new Token(Token.Type.GT, token.toString());
                }
                break;
            case '<':
                catToken();
                if (peekChar() == '=') {
                    getChar();
                    catToken();
                    retSymbol = new Token(Token.Type.LE, token.toString());
                } else {
                    retSymbol = new Token(Token.Type.LT, token.toString());
                }
                break;
            default:
                // 处理保留字和标识符
                if (isLetter() || ch == '_') {
                    catToken();
                    getChar();
                    while (isIdent()) {
                        // todo: 检查ident 终结符
                        catToken();
                        getChar();
                    }
                    retract();
                    if (Token.isReservedWord(token.toString())) {
                        retSymbol = new Token(Token.Type.valueOf(token.toString().toUpperCase()), token.toString());
                    } else {
                        retSymbol = new Token(Token.Type.IDENTIFIER, token.toString());
                    }
                    break;
                }
                // 处理整型
                if (isDigit()) {
                    // whole-number part
                    retSymbol = getSymbol_digit();
                    break;
                }
                // 未被识别
                throw new SyntaxError("Invalid character: " + ch);
        }

        // 尝试步进
        moveForward();

        if (recursive) {
            retSymbol = getSymbol();
        }
        //System.err.println(token);
        return retSymbol;
    }

    public static void run() throws SyntaxError, IOException {
        Token curToken;
        do{
            curToken = getSymbol();
            tokenArray.append(curToken);
        }while (!curToken.type.equals(Token.Type.EOF));
    }
}
