package frontend.lexer;

import java.util.ArrayList;
import java.util.Arrays;

import exception.SyntaxError;

public class TokenArray {
    public ArrayList<Token> tokens = new ArrayList<>();
    private static boolean DEBUG_MODE = false;

    public int index = 0;


    public void append(Token element) {
        tokens.add(element);
    }

    public Token getToken() {
        return tokens.get(index);
    }

    public void setToken(int index, Token newElement) {
        tokens.set(index, newElement);
    }

    public int getSize() {
        return tokens.size();
    }

    public Token next() {
        index += 1;
        return tokens.get(index);
    }

    public Token ahead(int count) {
        return tokens.get(index + count);
    }

    public boolean isEnd() {
        return index >= tokens.size() || tokens.get(index).type == Token.Type.EOF;
    }


    public Token consumeToken(Token.Type type) throws SyntaxError {
        if (isEnd()) {
            throw new SyntaxError("Unexpected EOF");
        }
        Token token = tokens.get(index);
        if (token.type == type) {
            if (DEBUG_MODE) {
                System.err.println("consume: " + token.type.toString());
            }
            index++;
            return token;
        }
        throw new SyntaxError("Expected " + type + " but got " + token.type.toString());
    }

    public boolean checkAndSkip(Token.Type type) {
        Token token = tokens.get(index);
        if (token.type == type) {
            index++;
            return true;
        }
        return false;
    }

    public boolean checkAndSkip(Token.Type... types) {
        Token token = tokens.get(index);
        for (Token.Type type :
                types) {
            if (token.type == type) {
                index++;
                return true;
            }
        }
        return false;
    }

    public Token consumeToken(Token.Type... types) throws SyntaxError {
        if (isEnd()) {
            throw new SyntaxError("Unexpected EOF");
        }
        Token token = tokens.get(index);
        for (Token.Type type : types) {
            if (token.type == type) {
                if (DEBUG_MODE) {
                    System.err.println("consume: " + type.toString());
                }
                index++;
                return token;
            }
        }
        for (int i = 0; i < index; i++) {
            System.err.println(tokens.get(i).content);
        }
        throw new SyntaxError("Expected " + Arrays.toString(types) + " but got " + token.type.toString());
    }

    public boolean check(Token.Type... types) {
        Token token = tokens.get(index);
        for (Token.Type type : types) {
            if (token.type == type) {
                return true;
            }
        }
        return false;
    }

    public boolean check(int count, Token.Type... types) {
        if (index + count >= tokens.size()) {
            return false;
        }
        Token token = tokens.get(index + count);
        for (Token.Type type : types) {
            if (token.type == type) {
                return true;
            }
        }
        return false;
    }
}
