package frontend.lexer;

public class CharType {

    public static boolean isdigit(char c) {
        return Character.isDigit(c);
    }

    public  static boolean isUpper(char c) {
        return Character.isUpperCase(c);
    }

    public  static boolean isLower(char c) {
        return Character.isLowerCase(c);
    }

    public static  boolean isLetter(char c) {
        return Character.isLetter(c);
    }

    public static  boolean isIdent(char c) {
        return isdigit(c) || isLetter(c) || (c == '_');
    }

    public  static boolean isNumber(char c) {
        return isdigit(c) || (c == '.') || isLetter(c);
    }

    public  static  boolean isSpecialNum(String str) {
        String hexFloat ="(0(x|X)[0-9A-Fa-f]*\\.[0-9A-Fa-f]*((p|P|e|E)(\\+|\\-)?[0-9A-Fa-f]*)?)|" +
                "(0(x|X)[0-9A-Fa-f]*[\\.]?[0-9A-Fa-f]*(p|P|e|E)((\\+|\\-)?[0-9A-Fa-f]*)?)";
        String hexInt = "0(x|X)[0-9A-Fa-f]+";

        String sciNumber = "^[+-]?\\d*\\.?\\d+[Ee][+-]?$";

        return str.matches(hexFloat) || str.matches(hexInt) || str.matches(sciNumber);
    }

    public  static boolean isBlank(char c) {
        return c == ' ' || c == '\r' || c == '\n' || c == '\t';
    }

}
