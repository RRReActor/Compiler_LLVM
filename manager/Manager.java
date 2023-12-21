package manager;

import frontend.semantic.SymTable;
import frontend.semantic.Symbol;
import mir.Function;
import mir.GlobalValue;
import mir.Module;
import mir.Type;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class Manager {

    private final ArrayList<String> outputListWithoutStr = new ArrayList<>();
    private final ArrayList<String> outputListOfStr = new ArrayList<>();
    private final ArrayList<String> outputList = new ArrayList<>();

    private final Module module;

    public Manager(SymTable globalSymTable, ArrayList<String> globalStrings, ArrayList<GlobalValue> globalValues) {
        module = new Module(globalSymTable, globalStrings, globalValues);
    }

    public Module getModule() {
        return module;
    }

    public static class ExternFunc {
        public static final Function MEMSET = new Function(Type.VoidType.VOID_TYPE, "memset",
                new Type.PointerType(Type.BasicType.I32_TYPE), Type.BasicType.I32_TYPE, Type.BasicType.I32_TYPE);
        public static final Function GETINT = new Function(Type.BasicType.I32_TYPE, "getint");
        public static final Function PUTINT = new Function(Type.VoidType.VOID_TYPE, "putint", Type.BasicType.I32_TYPE);
        public static final Function GETCH = new Function(Type.BasicType.I32_TYPE, "getch");
        public static final Function GETFLOAT = new Function(Type.BasicType.F32_TYPE, "getfloat");
        public static final Function PUTCH = new Function(Type.VoidType.VOID_TYPE, "putch", Type.BasicType.I32_TYPE);
        public static final Function PUTFLOAT = new Function(Type.VoidType.VOID_TYPE, "putfloat", Type.BasicType.F32_TYPE);
        public static final Function STARTTIME = new Function(Type.VoidType.VOID_TYPE, "starttime");
        public static final Function STOPTIME = new Function(Type.VoidType.VOID_TYPE, "stoptime");
        public static final Function GETARRAY = new Function(Type.BasicType.I32_TYPE, "getarray", new Type.PointerType(Type.BasicType.I32_TYPE));
        public static final Function GETFARRAY = new Function(Type.BasicType.I32_TYPE, "getfarray", new Type.PointerType(Type.BasicType.F32_TYPE));
        public static final Function PUTARRAY = new Function(Type.VoidType.VOID_TYPE, "putarray", Type.BasicType.I32_TYPE, new Type.PointerType(Type.BasicType.I32_TYPE));
        public static final Function PUTFARRAY = new Function(Type.VoidType.VOID_TYPE, "putfarray", Type.BasicType.I32_TYPE, new Type.PointerType(Type.BasicType.F32_TYPE));
        public static final Function PUTSTR = new Function(Type.VoidType.VOID_TYPE, "putstr", new Type.PointerType(Type.BasicType.I8_TYPE));
        public static final Function PRINTF = new Function(Type.VoidType.VOID_TYPE, "printf");

        public static final HashMap<String, Function> externFunctions = new HashMap<>() {{
            put(MEMSET.getName(), MEMSET);
            put(GETINT.getName(), GETINT);
            put(PUTINT.getName(), PUTINT);
            put(GETCH.getName(), GETCH);
            put(PUTCH.getName(), PUTCH);
            put(PUTSTR.getName(), PUTSTR);
            put(GETFLOAT.getName(), GETFLOAT);
            put(PUTFLOAT.getName(), PUTFLOAT);
            put(STARTTIME.getName(), STARTTIME);
            put(STOPTIME.getName(), STOPTIME);
            put(GETARRAY.getName(), GETARRAY);
            put(GETFARRAY.getName(), GETFARRAY);
            put(PUTARRAY.getName(), PUTARRAY);
            put(PUTFARRAY.getName(), PUTFARRAY);
            put(PRINTF.getName(), PRINTF);
        }};
    }


    public void outputLLVM(String name) throws FileNotFoundException {
        OutputStream out = new FileOutputStream(name);
        outputListWithoutStr.clear();
        HashMap<String, Function> functions = module.getFunctions();
        SymTable globalSymTable = module.getGlobalSymTable();
        ArrayList<String> globalStrings = module.getGlobalStrings();


        // 字符处理
        for (int i = 0; i < globalStrings.size(); i++) {
            outputListOfStr.add("@.str_" + (i + 1)  + " = constant [" + str2llvmIR(globalStrings.get(i).substring(1, globalStrings.get(i).length() - 1)));
        }

        //全局变量

        // 未定义常量表
        for (Map.Entry<Type, GlobalValue> entry : GlobalValue.undefTable.entrySet()) {
            outputListWithoutStr.add(String.format("%s = global %s", entry.getValue().getDescriptor(), entry.getValue().initValue.toString()));
        }


        for (Map.Entry<String, Symbol> globalSymbolEntry :
                globalSymTable.getSymbolMap().entrySet()) {
            outputListWithoutStr.add(String.format("@%s = global %s", globalSymbolEntry.getKey(), globalSymbolEntry.getValue().getInitValue().toString()));
        }


        //函数声明
         for (Map.Entry<String, Function> functionEntry :
                functions.entrySet()) {
            if (functionEntry.getValue().isExternal()) {
                Function function = functionEntry.getValue();
                if (functionEntry.getKey().equals(ExternFunc.PRINTF.getName())) {
                    // 调整为 putstr, putint, putch
                    outputListWithoutStr.add("declare void @putstr(i8*)");
                    outputListWithoutStr.add("declare void @putint(i32)");

                } else {
                    // 过滤 putint
                    if(functionEntry.getKey().equals(ExternFunc.PUTINT.getName())) {
                        continue;
                    }
                    outputListWithoutStr.add(String.format("declare %s @%s(%s)", function.getRetType().toString(), functionEntry.getKey(), function.FArgsToString()));
                }
            }
        }

        //函数定义
        for (Map.Entry<String, Function> functionEntry :
                functions.entrySet()) {
            Function function = functionEntry.getValue();
            if (function.isDeleted() || function.isExternal()) {
                continue;
            }
            for (String s : function.output()) {
                // 特殊处理 printf， 转化为使用 putch, putint, putstr 输出
                if(s.startsWith("\tREPLACE_PRINTF")) {
                    StringBuilder sb = new StringBuilder();
                    String[] args = s.split(",");
                    // 用正则获取<globalStrIdx> 中的 序号
                    int globalStrIdx = Integer.parseInt(args[0].replaceAll("[^0-9]", "")) - 1;
                    String formatStr = globalStrings.get(globalStrIdx);
                    // 去掉头尾的双引号
                    formatStr = formatStr.substring(1, formatStr.length() - 1);
                    String[] formatStrList = formatStr.split("%d");
                    // 字符序号
                    int str_idx = 0;
                    // 参数序号
                    int arg_idx = 1;
                    // 当前字符长度
                    int length = 0;
                    while(str_idx < formatStrList.length || arg_idx < args.length) {
                        if(str_idx < formatStrList.length) {
                            // 在全局定义字符串 str_idx_subIdx
                            String curName = "str_" + (globalStrIdx + 1) + "_" + (str_idx + 1);
                            outputListOfStr.add("@.str_" + (globalStrIdx + 1) + "_" + (str_idx + 1) + " = constant [" + str2llvmIR(formatStrList[str_idx]) + "\n");
                            // 计算当前字符串长度
                            length = getStrlen(formatStrList[str_idx]);
                            // %str_idx_subIdx = ptr
                            sb.append("\t%").append(curName)
                                    .append(" = getelementptr [").append(length).append(" x i8], [")
                                    .append(length).append(" x i8]* @.").append(curName).append(", i32 0, i32 0\n");
                            // 调用putstr输出
                            sb.append("\tcall void @putstr(i8* %").append(curName).append(")\n");
                            str_idx++;
                        }
                        if(arg_idx < args.length) {
                            sb.append("\tcall void @putint(").append(args[arg_idx]).append(")\n");
                            arg_idx++;
                        }
                    }
                    outputListWithoutStr.add(sb.toString());
//                    outputList.add("replace done");

//                    continue;
                }
                else {
                    outputListWithoutStr.add(s);
                }
            }
//            outputList.addAll(function.output());
        }
        outputList.addAll(outputListOfStr);
        outputList.addAll(outputListWithoutStr);
        streamOutput(out, outputList);

    }

    public HashMap<String, Function> getFunctions() {
        return module.getFunctions();
    }

    public void addFunction(Function function) {
        module.addFunction(function);
    }


    //region outputLLVM IR
    private int countOfSubStr(String str, String sub) {
        int count = 0;
        int index = str.indexOf(sub);
        while (index != -1) {
            count++;
            index = str.indexOf(sub, index + sub.length());
        }
        return count;
    }

    private int getStrlen(String str2) {
        String str = str2;
        str = "\"" + str.replace("\\n", "\\0A");
        str += "\\00\"";
        int length = str.length() - 2;
        length -= (countOfSubStr(str, "\\0A") + countOfSubStr(str, "\\00")) * 2;
        return length;
    }



    private String str2llvmIR(String str) {
        str = "\"" + str.replace("\\n", "\\0A");
        str += "\\00\"";
        int length = str.length() - 2;
        length -= (countOfSubStr(str, "\\0A") + countOfSubStr(str, "\\00")) * 2;
        StringBuilder sb = new StringBuilder();
        sb.append(length).append(" x i8] c");
        sb.append(str);
        return sb.toString();
    }

    private static void streamOutput(OutputStream fop, ArrayList<String> outputStringList) {
        OutputStreamWriter writer;
        writer = new OutputStreamWriter(fop, StandardCharsets.UTF_8);
        for (String t : outputStringList) {
            try {
                writer.append(t).append("\n");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        try {
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            fop.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    //end region

    public static class BufferReader {
        private static BufferedInputStream src;
        public static int line = 1;

        public static void init(String src) {
            try {
                FileInputStream srcStream = new FileInputStream(src);
                BufferReader.src = new BufferedInputStream(srcStream);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }


        public static char peekChar() {
            src.mark(1);
            try {
                char c = (char) src.read();
                src.reset();
                return c;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        public static void retract() {
            try {
                src.reset();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        public static int getChar() {

            src.mark(1);
            try {
                char ch = (char) src.read();
                if (ch == '\n') {
                    line++;
                }
                return ch;
            } catch (IOException e) {
                if (e instanceof EOFException)
                    return (char) -1;
                else throw new RuntimeException(e);
            }
        }

        public static boolean reachEOF() throws IOException {
            return (src.available() <= 0);
        }


    }
}
