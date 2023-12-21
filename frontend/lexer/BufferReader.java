package frontend.lexer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.ArrayList;

public class BufferReader {
    private final BufferedInputStream src;
    public static int line = 1;

    public BufferReader(BufferedInputStream src){
        this.src = src;
    }

    public int getChar() throws IOException {

        int c = src.read();
        while(CharType.isBlank((char) c)) {
            if(c == '\n'){
                line ++;
            }
            c = src.read();
        }
        return c;
    }

    public boolean reachEOF() throws IOException {
        boolean tmp = (src.available() <= 0);
        return tmp;
    }


}
