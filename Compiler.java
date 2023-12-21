import arg.Arg;
import frontend.Visitor;
import frontend.lexer.Lexer;
import frontend.lexer.TokenArray;
import frontend.syntaxChecker.Ast;
import frontend.syntaxChecker.Parser;
import manager.Manager;
import midend.*;
import mir.Function;
import mir.Module;


// Press Shift twice to open the Search Everywhere dialog and type `show whitespaces`,
// then press Enter. You can now see whitespace characters in your code.
public class Compiler {

    public static void main(String[] args) {


        String[] tmp = {"-o", "llvm_ir.txt", "testfile.txt", "-O1"};
        Arg arg = Arg.parse(tmp);
        try {
            // lex
            //System.err.println("Lexer here");
            Manager.BufferReader.init(arg.srcFileName);
            TokenArray tokenArray = new TokenArray();
            Lexer.init(tokenArray);
            Lexer.run();
            //System.err.println("Lexer work well, now it is parser");
            // parse
            //System.err.println("Parser here");
            Parser parser = new Parser(tokenArray);
            Ast ast = parser.parseAst();
            //System.err.println("Parser work well, now it is visitor");

            // visit
            //System.err.println("Visitor here");
            Visitor visitor = new Visitor();
            visitor.visitAst(ast);
            //visitor.getManager().outputLLVM(arg.outPath);
            //System.err.println("Visitor work well, now it is midend");

            // midend
            //System.err.println("Midend here");
            Module module = visitor.getManager().getModule();
            if (arg.opt) {

                for (Function function : module.getFuncSet()) {
                    if (function.isExternal()) {
                        continue;
                    }
                    function.buildControlFlowGraph();
                }
                FunctionInline.run(module);

                //dead code delete
                for (Function function : module.getFuncSet()) {
                    if (function.isExternal()) {
                        continue;
                    }
                    function.buildControlFlowGraph();
                }
                DeadCodeDelete.run(module);

                //mem2reg
                for (Function function : module.getFuncSet()) {
                    if (function.getBlocks().getSize() == 0) {
                        continue;
                    }
                    function.buildControlFlowGraph();
//                    function.checkCFG();
                    function.buildDominanceGraph();
                    function.runMem2Reg();
                }
            }

            visitor.getManager().outputLLVM(arg.outPath);

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(e.getClass().getSimpleName().length());
        }
    }

}