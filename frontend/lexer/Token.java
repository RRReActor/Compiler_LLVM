package frontend.lexer;


public class Token {
    public TokenType tokenType;
    public String content;
    public int line;

    public Token(TokenType tokenType, String content, int line) {
        this.tokenType = tokenType;
        this.content = content;
        this.line = line;
    }

    public Token(TokenType tokenType, String content) {
        // 功能用token
        this.tokenType = tokenType;
        this.content = content;
        line = -1;
    }
}
