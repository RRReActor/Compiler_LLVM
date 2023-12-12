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
            outputListOfStr.add("@.str_" + (i + 1) + " = constant [" + str2llvmIR(globalStrings.get(i).substring(1, globalStrings.get(i).length() - 1)));
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
                    outputListWithoutStr.add("declare void @" + ExternFunc.PRINTF.getName() + "(ptr, ...)");
                } else {
                    outputListWithoutStr.add(String.format("declare %s @%s(%s)", function.getRetType().toString(), functionEntry.getKey(), function.FArgsToString()));
                }
            }
        }

        //函数定义
        int format_str_idx = 0;
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
                    // 用正则获取<strIdx> 中的 序号
                    int strIdx = Integer.parseInt(args[0].replaceAll("[^0-9]", "")) - 1;
                    String formatStr = globalStrings.get(strIdx);
                    // 仅含整数输出，以 %d 分割
                    if(formatStr.matches(".*%d.*")) {
                        // 去掉头尾的双引号
                        formatStr = formatStr.substring(1, formatStr.length() - 1);
                        String[] formatStrs = formatStr.split("%d");
                        if(!formatStrs[0].equals("")){
                            outputListOfStr.add("@.format_str_" + format_str_idx + " = constant [" + str2llvmIR(formatStrs[0]));
                            format_str_idx++;
                            sb.append("\tcall void @putstr(ptr ").append("@.format_str_" + format_str_idx + ")\n");
                        }

                        for(int i = 1; i < formatStrs.length; i++) {
                            sb.append("\tcall void @putint(i32 ").append(args[i]).append(")\n");
                            outputListOfStr.add("@.format_str_" + format_str_idx + " = constant [" + str2llvmIR(formatStrs[i]));
                            format_str_idx++;
                            sb.append("\tcall void @putstr(ptr ").append("@.format_str_" + format_str_idx + ")\n");

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

}
