package frontend.syntaxChecker;

import frontend.lexer.Token;
import frontend.lexer.TokenArray;
import exception.SyntaxError;


import java.util.ArrayList;

/**
 * 递归下降
 */
public class Parser {
    private final TokenArray tokenArray;

    public Parser(TokenArray tokenArray) {
        this.tokenArray = tokenArray;
    }

    public Ast parseAst() throws SyntaxError {
        ArrayList<Ast.CompUnit> compUnits = new ArrayList<Ast.CompUnit>();
        while (!tokenArray.isEnd()) {
            compUnits.add(parseDecl());
        }
        return new Ast(compUnits);
    }

    private Ast.Decl parseDecl() throws SyntaxError {
        if(tokenArray.check(2, Token.Type.L_PAREN)) {
            return parseFuncDef();
        }
        if (tokenArray.check(Token.Type.CONST)) {
            return parseConstDecl();
        } else {
            return parseVarDecl();
        }

    }

    private Ast.FuncDef parseFuncDef() throws SyntaxError {
        Token funcType = tokenArray.consumeToken(Token.Type.INT, Token.Type.FLOAT, Token.Type.VOID);
        Ast.Ident ident = parseIdent();
        tokenArray.consumeToken(Token.Type.L_PAREN);
        boolean hasFuncFParams = !tokenArray.checkAndSkip(Token.Type.R_PAREN);
        if(hasFuncFParams) {
            ArrayList<Ast.FuncFParam> funcFParams = parseFuncFParams();
            tokenArray.consumeToken(Token.Type.R_PAREN);
            Ast.Block block = parseBlock();
            return new Ast.FuncDef(funcType, ident, funcFParams, block);
        }
        Ast.Block block = parseBlock();
        return new Ast.FuncDef(funcType, ident, block);

    }

    private Ast.ConstDecl parseConstDecl() throws SyntaxError {
        tokenArray.consumeToken(Token.Type.CONST);
        Ast.Btype btype = parseBtype();
        ArrayList<Ast.ConstDef> constDefs = new ArrayList<Ast.ConstDef>();
        do{
            Ast.ConstDef constDef = parseConstDef();
            constDefs.add(constDef);
        } while (tokenArray.checkAndSkip(Token.Type.COMMA));
        tokenArray.consumeToken(Token.Type.SEMI);
        return new Ast.ConstDecl(btype, constDefs);
    }

    private Ast.ConstDef parseConstDef() throws SyntaxError {
        Ast.Ident ident = parseIdent();
        if(tokenArray.checkAndSkip(Token.Type.L_BRACK)) {
            ArrayList<Ast.AddExp> addExps = new ArrayList<Ast.AddExp>();
            do {
                Ast.AddExp addExp = parseAddExp();
                addExps.add(addExp);
                tokenArray.consumeToken(Token.Type.R_BRACK);
            } while (tokenArray.checkAndSkip(Token.Type.L_BRACK));
            tokenArray.consumeToken(Token.Type.ASSIGN);
            Ast.ConstInitVal constInitVal = parseConstInitVal();
            return new Ast.ConstDef(ident, addExps, constInitVal);
        } else {
            tokenArray.consumeToken(Token.Type.ASSIGN);
            Ast.ConstInitVal constInitVal = parseConstInitVal();
            return new Ast.ConstDef(ident, constInitVal);
        }
    }

    private Ast.AddExp parseConstExp() throws SyntaxError {
        return parseAddExp();
    }

    private Ast.ConstInitVal parseConstInitVal() throws SyntaxError {
        if(tokenArray.checkAndSkip(Token.Type.L_BRACE)) {
            if(tokenArray.checkAndSkip(Token.Type.R_BRACE)) {
                return new Ast.ConstInitVal();
            } else {
                ArrayList<Ast.ConstInitVal> constInitVals = new ArrayList<Ast.ConstInitVal>();
                do {
                    Ast.ConstInitVal constInitVal = parseConstInitVal();
                    constInitVals.add(constInitVal);
                } while (tokenArray.checkAndSkip(Token.Type.COMMA));
                tokenArray.consumeToken(Token.Type.R_BRACE);
                return new Ast.ConstInitVal(constInitVals);
            }
        } else {
            Ast.AddExp constExp = parseConstExp();
            return new Ast.ConstInitVal(constExp);
        }
    }

    private Ast.VarDecl parseVarDecl() throws SyntaxError {
        Ast.Btype btype = parseBtype();
        ArrayList<Ast.VarDef> varDefs = new ArrayList<Ast.VarDef>();
        do {
            Ast.VarDef varDef = parseVarDef();
            varDefs.add(varDef);
        } while (tokenArray.checkAndSkip(Token.Type.COMMA));
        tokenArray.consumeToken(Token.Type.SEMI);
        return new Ast.VarDecl(btype, varDefs);
    }

    private Ast.VarDef parseVarDef() throws SyntaxError {
        Ast.Ident ident = parseIdent();
        ArrayList<Ast.VarSuffix> varSuffixes = new ArrayList<Ast.VarSuffix>();
        ArrayList<Ast.AddExp> addExps = new ArrayList<>();
        if(tokenArray.check(Token.Type.L_BRACK)) {
            do {
                Ast.VarSuffix varSuffix = parseVarSuffix();
                varSuffixes.add(varSuffix);
            } while (tokenArray.check(Token.Type.L_BRACK));
            for (Ast.VarSuffix varSuffix:
                 varSuffixes) {
                addExps.add(varSuffix.getExp());
            }
            if (tokenArray.checkAndSkip(Token.Type.ASSIGN)) {
                Ast.VarInitVal initVal = parseInitVal();
                return new Ast.VarDef(ident, addExps, initVal);
            }
            return new Ast.VarDef(ident, addExps);
        } else if (tokenArray.checkAndSkip(Token.Type.ASSIGN)) {
            Ast.VarInitVal initVal = parseInitVal();
            return new Ast.VarDef(ident, initVal);
        } else {
            return new Ast.VarDef(ident);
        }
    }

    private Ast.VarInitVal parseInitVal() throws SyntaxError {
        if(tokenArray.checkAndSkip(Token.Type.L_BRACE)) {
            if(tokenArray.checkAndSkip(Token.Type.R_BRACE)) {
                return new Ast.VarInitVal();
            } else {
                ArrayList<Ast.VarInitVal> initVals = new ArrayList<Ast.VarInitVal>();
                do {
                    Ast.VarInitVal initVal = parseInitVal();
                    initVals.add(initVal);
                } while (tokenArray.checkAndSkip(Token.Type.COMMA));
                tokenArray.consumeToken(Token.Type.R_BRACE);
                return new Ast.VarInitVal(initVals);
            }
        } else {
            Ast.AddExp exp = parseConstExp();
            return new Ast.VarInitVal(exp);
        }
    }

    private Ast.Block parseBlock() throws SyntaxError {
        tokenArray.consumeToken(Token.Type.L_BRACE);
        ArrayList<Ast.BlockItem> blockItems = new ArrayList<Ast.BlockItem>();
        while(!tokenArray.checkAndSkip(Token.Type.R_BRACE))
        {
            Ast.BlockItem blockItem = parseBlockItem();
            blockItems.add(blockItem);
        }
        return new Ast.Block(blockItems);
    }

    private Ast.BlockItem parseBlockItem() throws SyntaxError {
        if (tokenArray.check(Token.Type.CONST, Token.Type.INT, Token.Type.FLOAT)) {
            return parseDecl();
        } else {
            return parseStmt();
        }
    }

    private Ast.Lval exactLval(Ast.AddExp exp) throws SyntaxError {
        Ast.Lval lval;
        try {
            lval = exp.getMulExp().getUnaryExp().getPrimaryExp().getLval();
            assert lval != null;
        } catch (NullPointerException e) {
            throw new SyntaxError("Expected Lval to assign");
        }
        return lval;
    }

    /**
     * Stmt → LVal ‘=’ Exp ‘;’ | [Exp] ‘;’ | Block | ‘if’ ‘(’ Cond ‘)’ Stmt [ ‘else’ Stmt ] |
     * ‘while’ ‘(’ Cond ‘)’ Stmt | ‘break’ ‘;’ | ‘continue’ ‘;’ | ‘return’ [Exp] ‘;’
     */
    private Ast.Stmt parseStmt() throws SyntaxError {
        Token firstToken = tokenArray.ahead(0);
        switch (firstToken.type) {
            case IDENTIFIER -> {
                if(tokenArray.ahead(1).type == Token.Type.L_PAREN) {
                    Ast.AddExp exp = parseAddExp();
                    tokenArray.consumeToken(Token.Type.SEMI);
                    return new Ast.ExpStmt(exp);
                }
                Ast.AddExp exp = parseAddExp();
                if (!tokenArray.check(Token.Type.ASSIGN)) {
                    return new Ast.ExpStmt(exp);
                }
                Ast.Lval lval = exactLval(exp);
                tokenArray.consumeToken(Token.Type.ASSIGN);
                Ast.AddExp rExp = parseAddExp();
                tokenArray.consumeToken(Token.Type.SEMI);
                return new Ast.AssignStmt(lval, rExp);
            }
            case SEMI -> {
                tokenArray.consumeToken(Token.Type.SEMI);
                return new Ast.VoidStmt();
            }
            case L_BRACE -> {
                Ast.Block block = parseBlock();
                return new Ast.BlockStmt(block);
            }
            case IF -> {
                tokenArray.consumeToken(Token.Type.IF);
                tokenArray.consumeToken(Token.Type.L_PAREN);
                Ast.Cond cond = parseCond();
                tokenArray.consumeToken(Token.Type.R_PAREN);
                Ast.Stmt stmt = parseStmt();
                if(tokenArray.checkAndSkip(Token.Type.ELSE)) {
                    Ast.Stmt stmt1 = parseStmt();
                    return new Ast.IfElStmt(cond, stmt, stmt1);
                }
                return new Ast.IfStmt(cond, stmt);
            }
            case WHILE -> {
                tokenArray.consumeToken(Token.Type.WHILE);
                tokenArray.consumeToken(Token.Type.L_PAREN);
                Ast.Cond cond = parseCond();
                tokenArray.consumeToken(Token.Type.R_PAREN);
                Ast.Stmt stmt = parseStmt();
                return new Ast.WhileStmt(cond, stmt);
            }
            case FOR -> {
                tokenArray.consumeToken(Token.Type.FOR);
                tokenArray.consumeToken(Token.Type.L_PAREN);
                Ast.Stmt init = null;
                Ast.Cond cond = null;
                Ast.Stmt step = null;
                if(!tokenArray.checkAndSkip(Token.Type.SEMI)) {
                    init = parseStmt();
                }
                if(!tokenArray.checkAndSkip(Token.Type.SEMI)) {
                    cond = parseCond();
                    tokenArray.consumeToken(Token.Type.SEMI);
                }
                if(!tokenArray.checkAndSkip(Token.Type.R_PAREN)) {
                    // 不含分号的赋值语句
                    Ast.AddExp exp = parseAddExp();
                    Ast.Lval lval = exactLval(exp);
                    tokenArray.consumeToken(Token.Type.ASSIGN);
                    Ast.AddExp rExp = parseAddExp();
                    step = new Ast.AssignStmt(lval, rExp);
                    tokenArray.consumeToken(Token.Type.R_PAREN);
                }
                Ast.Stmt stmt = parseStmt();
                return new Ast.ForStmt(init, cond, step, stmt);
            }
            case BREAK -> {
                tokenArray.consumeToken(Token.Type.BREAK);
                tokenArray.consumeToken(Token.Type.SEMI);
                return new Ast.BreakStmt();
            }
            case CONTINUE -> {
                tokenArray.consumeToken(Token.Type.CONTINUE);
                tokenArray.consumeToken(Token.Type.SEMI);
                return new Ast.ContinueStmt();
            }
            case RETURN -> {
                tokenArray.consumeToken(Token.Type.RETURN);
                if(tokenArray.checkAndSkip(Token.Type.SEMI)) {
                    return new Ast.ReturnStmt();
                }
                Ast.AddExp exp = parseAddExp();
                tokenArray.consumeToken(Token.Type.SEMI);
                return new Ast.ReturnStmt(exp);
            }
            default -> {
                Ast.AddExp exp = parseAddExp();
                tokenArray.consumeToken(Token.Type.SEMI);
                return new Ast.ExpStmt(exp);
            }
        }
    }

    private Ast.Cond parseCond() throws SyntaxError {
        return parseLOrExp();
    }

    private Ast.LOrExp parseLOrExp() throws SyntaxError {
        ArrayList<Ast.LAndExp> lAndExps = new ArrayList<Ast.LAndExp>();
        do {
            Ast.LAndExp lAndExp = parseLAndExp();
            lAndExps.add(lAndExp);
        } while (tokenArray.checkAndSkip(Token.Type.LOR));
        return new Ast.LOrExp(lAndExps);
    }

    private Ast.LAndExp parseLAndExp() throws SyntaxError {
        ArrayList<Ast.EqExp> eqExps = new ArrayList<Ast.EqExp>();
        do {
            Ast.EqExp eqExp = parseEqExp();
            eqExps.add(eqExp);
        } while (tokenArray.checkAndSkip(Token.Type.LAND));
        return new Ast.LAndExp(eqExps);
    }

    private Ast.EqExp parseEqExp() throws SyntaxError {
        ArrayList<Ast.RelExp> relExps = new ArrayList<Ast.RelExp>();
        ArrayList<Token> eqOps = new ArrayList<>();
        do {
            Ast.RelExp relExp = parseRelExp();
            relExps.add(relExp);
            if (tokenArray.check(Token.Type.EQ, Token.Type.NE)) {
                Token eqOp = tokenArray.getToken();
                eqOps.add(eqOp);
            }
        } while (tokenArray.checkAndSkip(Token.Type.EQ, Token.Type.NE));
        return new Ast.EqExp(relExps, eqOps);
    }

    private Ast.RelExp parseRelExp() throws SyntaxError {
        ArrayList<Ast.AddExp> addExps = new ArrayList<Ast.AddExp>();
        ArrayList<Token> relOps = new ArrayList<>();
        do {
            Ast.AddExp addExp = parseAddExp();
            addExps.add(addExp);
            if (tokenArray.check(Token.Type.LE, Token.Type.GE, Token.Type.LT, Token.Type.GT)) {
                Token relOp = tokenArray.getToken();
                relOps.add(relOp);
            }
        } while (tokenArray.checkAndSkip(Token.Type.LE, Token.Type.GE, Token.Type.LT, Token.Type.GT));
        return new Ast.RelExp(addExps, relOps);
    }


    private ArrayList<Ast.FuncFParam> parseFuncFParams() throws SyntaxError {
        ArrayList<Ast.FuncFParam> funcFParams = new ArrayList<Ast.FuncFParam>();
        do {
            Ast.FuncFParam funcFParam = parseFuncFParam();
            funcFParams.add(funcFParam);
        } while (tokenArray.checkAndSkip(Token.Type.COMMA));
        return funcFParams;
    }

    private Ast.Btype parseBtype() throws SyntaxError {
        Token type = tokenArray.consumeToken(Token.Type.INT, Token.Type.FLOAT);
        return new Ast.Btype(type);
    }

    private Ast.Ident parseIdent() throws SyntaxError {
        Token ident = tokenArray.consumeToken(Token.Type.IDENTIFIER);
        return new Ast.Ident(ident);
    }

    private Ast.FuncFParam parseFuncFParam() throws SyntaxError {
        Ast.Btype btype = parseBtype();
        Ast.Ident ident = parseIdent();
        ArrayList<Ast.VarSuffix> varSuffixes = new ArrayList<>();
        if (tokenArray.check(Token.Type.L_BRACK) && tokenArray.check(1, Token.Type.R_BRACK)) {
            tokenArray.consumeToken(Token.Type.L_BRACK);
            tokenArray.consumeToken(Token.Type.R_BRACK);
            varSuffixes.add(new Ast.VarSuffix(true));
        }

        while (!(tokenArray.check(Token.Type.R_PAREN) || tokenArray.check(Token.Type.COMMA))) {
            Ast.VarSuffix varSuffix = parseVarSuffix();
            varSuffixes.add(varSuffix);
        }
        return new Ast.FuncFParam(btype, ident, varSuffixes);
    }

    private Ast.VarSuffix parseVarSuffix() throws SyntaxError {
        if(tokenArray.checkAndSkip(Token.Type.L_BRACK)) {
            Ast.AddExp exp = parseAddExp();
            tokenArray.consumeToken(Token.Type.R_BRACK);
            return new Ast.VarSuffix(exp);
        } else {
            return new Ast.VarSuffix();
        }
    }

    private Ast.AddExp parseAddExp() throws SyntaxError {
        Ast.MulExp mulExp = parseMulExp();
        Ast.AddExpSuffix addExpSuffix = parseAddExpSuffix();
        return new Ast.AddExp(mulExp, addExpSuffix);
    }

    private Ast.MulExp parseMulExp() throws SyntaxError {
        Ast.UnaryExp unaryExp = parseUnaryExp();
        Ast.MulExpSuffix mulExpSuffix = parseMulExpSuffix();
        return new Ast.MulExp(unaryExp, mulExpSuffix);
    }

    private Ast.AddExpSuffix parseAddExpSuffix() throws SyntaxError {
        if (tokenArray.check(Token.Type.ADD)) {
            Token addOp = tokenArray.consumeToken(Token.Type.ADD);
            Ast.MulExp mulExp = parseMulExp();
            Ast.AddExpSuffix addExpSuffix = parseAddExpSuffix();
            return new Ast.AddExpSuffix(mulExp, addExpSuffix, addOp);
        } else if (tokenArray.check(Token.Type.SUB)) {
            Token subOp = tokenArray.consumeToken(Token.Type.SUB);
            Ast.MulExp mulExp = parseMulExp();
            Ast.AddExpSuffix addExpSuffix = parseAddExpSuffix();
            return new Ast.AddExpSuffix(mulExp, addExpSuffix, subOp);
        } else {
            return new Ast.AddExpSuffix();
        }
    }

    private Ast.UnaryExp parseUnaryExp() throws SyntaxError {
        if(tokenArray.check(Token.Type.IDENTIFIER) && tokenArray.check(1, Token.Type.L_PAREN)) {
            Ast.Ident ident = parseIdent();
            tokenArray.consumeToken(Token.Type.L_PAREN);
            if(tokenArray.checkAndSkip(Token.Type.R_PAREN)) {
                return new Ast.UnaryExp(ident);
            }
            if (tokenArray.check(Token.Type.STR)) {
//                if(!ident.identifier.content.equals(Manager.ExternFunc.PRINTF.getName())) {
//                    throw new SyntaxError("Unexpeted string");
//                }
                Token str = tokenArray.consumeToken(Token.Type.STR);
                if (tokenArray.checkAndSkip(Token.Type.COMMA)) {
                    Ast.FuncRParams funcRParams = parseFuncRParams();
                    tokenArray.consumeToken(Token.Type.R_PAREN);
                    return new Ast.UnaryExp(ident, funcRParams, str);
                }
                tokenArray.consumeToken(Token.Type.R_PAREN);
                return new Ast.UnaryExp(ident, str);
            }
            Ast.FuncRParams funcRParams = parseFuncRParams();
            tokenArray.consumeToken(Token.Type.R_PAREN);
            return new Ast.UnaryExp(ident, funcRParams);
        } else if (tokenArray.check(Token.Type.NOT, Token.Type.ADD, Token.Type.SUB)) {
            Token unaryOp = tokenArray.consumeToken(Token.Type.NOT, Token.Type.ADD, Token.Type.SUB);
            Ast.UnaryExp unaryExp = parseUnaryExp();
            return new Ast.UnaryExp(unaryOp, unaryExp);
        } else {
            return new Ast.UnaryExp(parsePrimaryExp());
        }
    }

    private Ast.MulExpSuffix parseMulExpSuffix() throws SyntaxError {
        if(tokenArray.check(Token.Type.MUL, Token.Type.DIV, Token.Type.MOD)) {
            Token mulOp = tokenArray.consumeToken(Token.Type.MUL, Token.Type.DIV, Token.Type.MOD);
            Ast.UnaryExp unaryExp = parseUnaryExp();
            Ast.MulExpSuffix mulExpSuffix = parseMulExpSuffix();
            return new Ast.MulExpSuffix(unaryExp, mulExpSuffix, mulOp);
        } else {
            return new Ast.MulExpSuffix();
        }
    }

    private Ast.PrimaryExp parsePrimaryExp() throws SyntaxError {
        if(tokenArray.checkAndSkip(Token.Type.L_PAREN)) {
            Ast.AddExp exp = parseAddExp();
            tokenArray.consumeToken(Token.Type.R_PAREN);
            return new Ast.PrimaryExp(exp);
        } else if (tokenArray.check(Token.Type.IDENTIFIER)) {
            return new Ast.PrimaryExp(parseLval());
        } else {
            Token number = tokenArray.consumeToken(Token.Type.DEC_INT, Token.Type.OCT_INT, Token.Type.HEX_INT, Token.Type.DEC_FLOAT, Token.Type.HEX_FLOAT);
            return new Ast.PrimaryExp(number);
        }
    }

    private Ast.FuncRParams parseFuncRParams() throws SyntaxError {
        ArrayList<Ast.AddExp> funcRparams = new ArrayList<Ast.AddExp>();
        do {
            Ast.AddExp addExp = parseAddExp();
            funcRparams.add(addExp);
        } while (tokenArray.checkAndSkip(Token.Type.COMMA));
        return new Ast.FuncRParams(funcRparams);
    }

    private Ast.Lval parseLval() throws SyntaxError {
        Ast.Ident ident = parseIdent();
        Ast.LvalSuffix lvalSuffix = parseLvalSuffix();
        return new Ast.Lval(ident, lvalSuffix);
    }

    private Ast.LvalSuffix parseLvalSuffix() throws SyntaxError {
        if(tokenArray.checkAndSkip(Token.Type.L_BRACK)) {
            if (tokenArray.checkAndSkip(Token.Type.R_BRACK)) {
                Ast.LvalSuffix lvalSuffix = parseLvalSuffix();
                return new Ast.LvalSuffix(lvalSuffix);
            }
            Ast.AddExp exp = parseAddExp();
            tokenArray.consumeToken(Token.Type.R_BRACK);
            Ast.LvalSuffix lvalSuffix = parseLvalSuffix();
            return new Ast.LvalSuffix(exp, lvalSuffix);
        } else {
            return new Ast.LvalSuffix();
        }
    }

}
