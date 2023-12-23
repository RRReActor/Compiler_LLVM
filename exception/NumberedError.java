package exception;

public class NumberedError {
    private char type;
    private int line;

    public NumberedError(int line, char type) {
        this.type = type;
        this.line = line;
    }

    @Override
    public String toString() {
        return String.format("%d %c",line, type);
    }

    public int getLine() {
        return line;
    }
}
