package frontend;

import exception.NumberedError;
import exception.SemanticError;
import exception.SyntaxError;
import frontend.lexer.Token;
import frontend.semantic.Calculator;
import frontend.semantic.InitValue;
import frontend.semantic.SymTable;
import frontend.semantic.Symbol;
import frontend.syntaxChecker.Ast;
import manager.Manager;
import mir.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Visitor {
    private SymTable globalSymTable = new SymTable();
    public ArrayList<String> globalStr = new ArrayList<>();
    private ArrayList<GlobalValue> globalValues = new ArrayList<GlobalValue>();
    private final Manager manager = new Manager(globalSymTable, globalStr, globalValues);
    //初始时符号表为全局符号表
    private SymTable currentSymTable = globalSymTable;
    private BasicBlock currentBB = null;
    private BasicBlock currentEntry = null;

    private Function currentFunc = null;

    private boolean isGlobal = true;

    private int countOfBB = 0;

    private final Stack<Recorder> recorders = new Stack<>();

    private final Stack<Ast.Cond> conds = new Stack<>();

    private boolean isInLoop() {
        return !recorders.isEmpty();
    }

    private boolean isInCond() {
        return !conds.isEmpty();
    }

    public Manager getManager() {
        return manager;
    }


    public void visitAst(Ast ast) throws SemanticError {
        assert isGlobal && currentBB == null && currentFunc == null;
        ArrayList<Ast.CompUnit> compUnits = ast.getUnits();
        for (Ast.CompUnit compUnit :
                compUnits) {
            visitCompUnit(compUnit);
        }
    }

    private void visitCompUnit(Ast.CompUnit compUnit) throws SemanticError {
        visitDecl((Ast.Decl) compUnit);
    }

    private void visitDecl(Ast.Decl decl){
        try {
            if (decl instanceof Ast.ConstDecl) {
                visitConstDecl((Ast.ConstDecl) decl);
            } else if (decl instanceof Ast.VarDecl) {
                visitVarDecl((Ast.VarDecl) decl);
            } else if (decl instanceof Ast.FuncDef) {
                visitFuncDef((Ast.FuncDef) decl);
            }
        }catch (SemanticError e){
//            System.err.println(e.getMessage());
        }
    }

    private void visitConstDecl(Ast.ConstDecl constDecl) throws SemanticError {
        for (Ast.ConstDef constDef :
                constDecl.getConstDefs()) {
            visitConstDef(constDef, constDecl.getBtype());
        }
    }


    private void visitConstDef(Ast.ConstDef constDef, Ast.Btype btype) throws SemanticError {
        //判断定义类型
        Ast.Ident ident = constDef.getIdent();
        if (currentSymTable.hasSymbol(ident, false)) {
            //
            manager.addNumberedError(new NumberedError(manager.astRecorder.get(ident), 'b'));

            //System.err.println("Duplicated variable define" + ident.identifier.content);
            //throw new SemanticError();
        }
        Type defType = switch (btype.type.type) {
            case INT -> mir.Type.BasicType.I32_TYPE;
            case FLOAT -> mir.Type.BasicType.F32_TYPE;
            default -> throw new SemanticError("Wrong Type of variable" + btype.type.type);
        };
        //计算数组的维度
        Calculator calculator = new Calculator(currentSymTable);
        ArrayList<Integer> dims = new ArrayList<>();
        for (Ast.AddExp constExp :
                constDef.getConstExps()) {
            dims.add(calculator.evalConsInt(constExp));
        }
        for (int i = dims.size() - 1; i >= 0; i--) {
            defType = new Type.ArrayType(dims.get(i), defType);
        }
        //初始化, 如果未初始化，直接初始化成0
        InitValue initValue = null;
        Ast.ConstInitVal constInitVal = constDef.getConstInitVal();
        if (constInitVal.hasInitVal()) {
            if (defType.isInt32Ty() || defType.isFloatTy()) {
                if (constInitVal.getExp() == null) {
                    throw new SemanticError("Variable type could not be init by a list");
                }
                initValue = parseInitValue(defType, constInitVal.getExp());
            } else {
                if (constInitVal.getInitVals() == null) {
                    throw new SemanticError("Array type could not be init by a single value");
                }
                initValue = parseInitArray(defType, constInitVal.getInitVals(), true);
            }
        } else {
            if (defType.isInt32Ty()) {
                initValue = new InitValue.ValueInit(new Constant.ConstantInt(0), mir.Type.BasicType.I32_TYPE);
            } else if (defType.isFloatTy()) {
                initValue = new InitValue.ValueInit(new Constant.ConstantFloat(0), mir.Type.BasicType.F32_TYPE);
            } else if (defType.isArrayTy()) {
                initValue = new InitValue.ZeroArrayInit(defType);
            }
        }

        allocMem(defType, ident, initValue, true);
    }

    //为变量分配空间(前端进行alloca/load/store),由中端负责进行mem2reg
    private void allocMem(Type defType, Ast.Ident ident, InitValue initValue, boolean isConstant) throws SemanticError {
        Value address;
        if (isGlobal) {
            //全局变量不需要上述指令，直接插入符号表
            address = new GlobalValue(defType, ident, initValue);
        } else {
            assert currentBB != null;
            assert currentEntry != null;
            assert initValue != null;
            address = new Instruction.Alloc(currentEntry, defType);
            address.remove();
            currentEntry.addInstFirst((Instruction) address);
            if (initValue instanceof InitValue.ValueInit) {
                assert defType instanceof Type.BasicType;
                Value value = castType(((InitValue.ValueInit) initValue).getValue(), (Type.BasicType) defType);
                new Instruction.Store(currentBB, value, address);
            } else if (initValue instanceof InitValue.ExpInit) {
                assert defType instanceof Type.BasicType;
                Value value = castType(((InitValue.ExpInit) initValue).getResult(), (Type.BasicType) defType);
                new Instruction.Store(currentBB, value, address);
            } else {
                //数组
                //按照https://buaa-se-compiling.github.io/miniSysY-tutorial/lab7/part12_task.html的思想，先把内存memset成0，再store非零的值
                assert initValue instanceof InitValue.ArrayInit;
                assert defType instanceof Type.ArrayType;
                InitValue.Flatten flatten = initValue.flatten();
                Map<Integer, Value> nZeros = flatten.IndexOfNZero();
                Type.BasicType eleType = ((Type.ArrayType) defType).getBasicEleType();

                callMemset(address, (Type.ArrayType) defType);

                for (Map.Entry<Integer, Value> entry :
                        nZeros.entrySet()) {
                    ArrayList<Value> offsets = new ArrayList<>();
                    for (int i = 0; i < ((Type.ArrayType) defType).getDims().size(); i++) {
                        offsets.add(new Constant.ConstantInt(0));
                    }
                    offsets.add(new Constant.ConstantInt(entry.getKey()));
                    Value p = new Instruction.GetElementPtr(currentBB, address, eleType, offsets);
                    new Instruction.Store(currentBB, castType(entry.getValue(), eleType), p);
                }
            }
        }
        //插入符号表
        Symbol symbol = new Symbol(ident, defType, initValue, isConstant, address);
        if (isGlobal) {
            globalValues.add(new GlobalValue(defType, ident, initValue));
        }
        currentSymTable.addSymbol(symbol);
    }


    private void callMemset(Value address, Type.ArrayType arrayType) {
        if (!(address.getType() instanceof Type.PointerType)) {
            throw new AssertionError("Need a Pointer type");
        }
        int size = arrayType.getFlattenSize() * 4;
        Value ptr = address;
        if (!((Type.PointerType) address.getType()).getInnerType().isInt32Ty()) {
            ptr = new Instruction.BitCast(currentBB, ptr, new Type.PointerType(mir.Type.BasicType.I32_TYPE));
        }
        ArrayList<Value> params = new ArrayList<>();
        params.add(ptr);
        params.add(new Constant.ConstantInt(0));
        params.add(new Constant.ConstantInt(size));
        new Instruction.Call(currentBB, Manager.ExternFunc.MEMSET, params);
        manager.addFunction(Manager.ExternFunc.MEMSET);
    }


    /**
     * 类型转换，实际将llvm IR的zext指令拆为多种指令分别执行
     *
     * @param from（Src  Value）
     * @param to（Target Type）
     * @return 类型转换后的Value
     */
    private Value castType(Value from, Type.BasicType to) throws SemanticError {
        assert from != null;
        assert from.getType() instanceof Type.BasicType;
        Type.BasicType type = (Type.BasicType) from.getType();
        if (from instanceof Constant) {
            return castConstantType((Constant) from, to);
        }
        if (type.isFloatTy()) {
            if (to.isFloatTy()) return from;
            else if (to.isInt32Ty()) {
                return new Instruction.FPtosi(currentBB, from);
            } else if (to.isInt1Ty()) {
                return new Instruction.Fcmp(currentBB, Instruction.Fcmp.CondCode.NE, from, new Constant.ConstantFloat(0));
            }
        } else if (type.isInt32Ty()) {
            if (to.isInt32Ty()) return from;
            else if (to.isFloatTy()) {
                return new Instruction.SItofp(currentBB, from);
            } else if (to.isInt1Ty()) {
                return new Instruction.Icmp(currentBB, Instruction.Icmp.CondCode.NE, from, new Constant.ConstantInt(0));
            }
        } else if (type.isInt1Ty()) {
            if (to.isInt1Ty()) return from;
            else if (to.isFloatTy()) {
                return new Instruction.SItofp(currentBB, new Instruction.Zext(currentBB, from));
            } else if (to.isInt32Ty()) {
                return new Instruction.Zext(currentBB, from);
            }
        }
        throw new SemanticError("Unsupport cast type");
    }


    private Constant castConstantType(Constant from, Type.BasicType to) throws SemanticError {
        Type.BasicType type = (Type.BasicType) from.getType();
        if (type.isFloatTy()) {
            if (to.isFloatTy()) return from;
            else if (to.isInt32Ty()) {
                return new Constant.ConstantInt((int) ((float) from.getConstValue()));
            } else if (to.isInt1Ty()) {
                if ((float) from.getConstValue() == 0)
                    return new Constant.ConstantBool(0);
                else
                    return new Constant.ConstantBool(1);
            }
        } else if (type.isInt32Ty()) {
            if (to.isInt32Ty()) return from;
            else if (to.isFloatTy()) {
                return new Constant.ConstantFloat((float) ((int) from.getConstValue()));
            } else if (to.isInt1Ty()) {
                if ((int) from.getConstValue() == 0)
                    return new Constant.ConstantBool(0);
                else
                    return new Constant.ConstantBool(1);
            }
        } else if (type.isInt1Ty()) {
            if (to.isInt1Ty()) return from;
            else if (to.isFloatTy()) {
                return new Constant.ConstantFloat((float) ((int) from.getConstValue()));
            } else if (to.isInt32Ty()) {
                return new Constant.ConstantInt((int) (from.getConstValue()));
            }
        }
        throw new SemanticError("Unsupport cast type");
    }

    /**
     * 用栈实现递归，解析任意维数组的初始化
     *
     * @param type     (Array type)
     * @param initVals (不可能是null), ArrayList<T> T extends Ast.InitVal
     * @return ArrayInit
     * @throws SemanticError error
     */
    private <T extends Ast.InitVal> InitValue.ArrayInit parseInitArray(Type type, ArrayList<T> initVals, boolean isConstant) throws SemanticError {
        if (!(type instanceof Type.ArrayType)) {
            throw new SemanticError("Variable can not be init by a list");
        }

        if (initVals.size() == 1 && !initVals.get(0).hasInitVal()) {
            return new InitValue.ZeroArrayInit(type);
        }

        InitValue.ArrayInit arrayInit = new InitValue.ArrayInit(type);

        for (int i = 0; i < initVals.size(); i++) {
            T initVal = initVals.get(i);
            if (arrayInit.getSize() == ((Type.ArrayType) type).getSize()) {
                break;
            }
            if (!initVal.hasInitVal()) {
                assert ((Type.ArrayType) type).getEleType() instanceof Type.ArrayType;
                arrayInit.addElement(new InitValue.ZeroArrayInit(((Type.ArrayType) type).getEleType()));
                continue;
            }
            if (initVal.getExp() == null) {
                arrayInit.addElement(parseInitArray(((Type.ArrayType) type).getEleType(), initVal.getInitVals(), isConstant));
            } else {
                if (((Type.ArrayType) type).getEleType() instanceof Type.BasicType) {
                    if (isConstant)
                        arrayInit.addElement(parseInitValue(((Type.ArrayType) type).getEleType(), initVal.getExp()));
                    else arrayInit.addElement(parseInitExp(((Type.ArrayType) type).getEleType(), initVal.getExp()));
                } else {
                    ArrayList<T> subInitVals = new ArrayList<>();
                    int cnt = 0;
                    Type.ArrayType eleTp = (Type.ArrayType) ((Type.ArrayType) type).getEleType();
                    for (int j = 0; j < eleTp.getFlattenSize(); ) {
                        if ((j + i) >= initVals.size()) {
                            break;
                        }
                        subInitVals.add(initVals.get(j + i));
                        if (!initVals.get(cnt + i).hasInitVal()) {
                            j = j + eleTp.getDimSize();
                        } else {
                            j = j + initVals.get(cnt + i).getFlattenSize();
                        }
                        cnt++;
                    }
                    arrayInit.addElement(parseInitArray(((Type.ArrayType) type).getEleType(), subInitVals, isConstant));
                    i = i + cnt - 1;
                }
            }
        }

        while (arrayInit.getSize() < ((Type.ArrayType) type).getSize()) {
            if (((Type.ArrayType) type).getEleType() instanceof Type.ArrayType) {
                arrayInit.addElement(new InitValue.ZeroArrayInit(((Type.ArrayType) type).getEleType()));
            } else {
                arrayInit.addElement(new InitValue.ValueInit(castConstantType(new Constant.ConstantInt(0), ((Type.ArrayType) type).getBasicEleType()),
                        ((Type.ArrayType) type).getBasicEleType()));
            }
        }

        return arrayInit;

    }


    private InitValue.ValueInit parseInitValue(Type type, Ast.AddExp exp) throws SemanticError {
        if (exp == null) {
            throw new SemanticError("Global Variable must be init by Const Exp");
        }
        Calculator calculator = new Calculator(currentSymTable);
        Object val = calculator.evalConstExp(exp);
        if (type.isInt32Ty()) {
            if (val instanceof Integer) {
                return new InitValue.ValueInit(new Constant.ConstantInt((int) val), mir.Type.BasicType.I32_TYPE);
            } else {
                return new InitValue.ValueInit(new Constant.ConstantInt((int) ((float) val)), mir.Type.BasicType.I32_TYPE);
            }
        } else {
            assert type.isFloatTy();
            if (val instanceof Integer) {
                return new InitValue.ValueInit(new Constant.ConstantFloat((float) ((int) val)), mir.Type.BasicType.F32_TYPE);
            } else {
                return new InitValue.ValueInit(new Constant.ConstantFloat((float) val), mir.Type.BasicType.F32_TYPE);
            }
        }
    }

    private InitValue parseInitExp(Type type, Ast.AddExp exp) throws SemanticError {
        if (exp == null) {
            throw new SemanticError("Global Variable must be init by Const Exp");
        }
        Value val = visitExp(exp);
        if (val instanceof Constant) {
            return new InitValue.ValueInit(val, type);
        }
        return new InitValue.ExpInit(val, type);
    }

    private Value visitExp(Ast.AddExp exp) throws SemanticError {
        Value val = visitMulExp(exp.getMulExp());
        Ast.AddExpSuffix e = null;
        do {
            if (e == null) {
                e = exp.getAddExpSuffix();
            } else {
                e = e.getAddExpSuffix();
            }
            val = visitAddExpSuffix(val, e);
        } while (e.hasNext());
        return val;
    }


    private Value visitMulExp(Ast.MulExp exp) throws SemanticError {
        Value val = visitUnaryExp(exp.getUnaryExp());
        Ast.MulExpSuffix e = null;
        do {
            if (e == null) {
                e = exp.getMulExpSuffix();
            } else {
                e = e.getMulExpSuffix();
            }
            val = visitMulExpSuffix(val, e);
            if (val instanceof Constant && ((Constant) val).isZero()) {
                return new Constant.ConstantInt(0);
            }
        } while (e.hasNext());
        return val;
    }

    private Value visitAddExpSuffix(Value addExpPerfix, Ast.AddExpSuffix addExpSuffix) throws SemanticError {
        if (!addExpSuffix.hasMulExp()) {
            return addExpPerfix;
        }
        switch (addExpSuffix.getAddOp().type) {
            case ADD -> {
                Value val = visitMulExp(addExpSuffix.getMulExp());
                Value perfix;
                if (val.getType() == mir.Type.BasicType.F32_TYPE || addExpPerfix.getType() == mir.Type.BasicType.F32_TYPE) {
                    if (val instanceof Constant && addExpPerfix instanceof Constant) {
                        perfix = new Constant.ConstantFloat((float) castConstantType((Constant) val, mir.Type.BasicType.F32_TYPE).getConstValue()
                                + (float) castConstantType((Constant) addExpPerfix, mir.Type.BasicType.F32_TYPE).getConstValue());
                    } else {
                        perfix = new Instruction.BinaryOperation.FAdd(currentBB, mir.Type.BasicType.F32_TYPE, castType(addExpPerfix, mir.Type.BasicType.F32_TYPE), castType(val, mir.Type.BasicType.F32_TYPE));
                    }
                    //return visitAddExpSuffix(perfix, addExpSuffix.getAddExpSuffix());
                    return perfix;
                }
                if (val instanceof Constant && addExpPerfix instanceof Constant) {
                    perfix = new Constant.ConstantInt((int) castConstantType((Constant) val, mir.Type.BasicType.I32_TYPE).getConstValue()
                            + (int) castConstantType((Constant) addExpPerfix, mir.Type.BasicType.I32_TYPE).getConstValue());
                } else {
                    perfix = new Instruction.BinaryOperation.Add(currentBB, mir.Type.BasicType.I32_TYPE, castType(addExpPerfix, mir.Type.BasicType.I32_TYPE), castType(val, mir.Type.BasicType.I32_TYPE));
                }
                //return visitAddExpSuffix(perfix, addExpSuffix.getAddExpSuffix());
                return perfix;
            }
            case SUB -> {
                Value val = visitMulExp(addExpSuffix.getMulExp());
                Value perfix;
                if (val.getType() == mir.Type.BasicType.F32_TYPE || addExpPerfix.getType() == mir.Type.BasicType.F32_TYPE) {
                    if (val instanceof Constant && addExpPerfix instanceof Constant) {
                        perfix = new Constant.ConstantFloat((float) castConstantType((Constant) addExpPerfix, mir.Type.BasicType.F32_TYPE).getConstValue()
                                - (float) castConstantType((Constant) val, mir.Type.BasicType.F32_TYPE).getConstValue());
                    } else {
                        perfix = new Instruction.BinaryOperation.FSub(currentBB, mir.Type.BasicType.F32_TYPE, castType(addExpPerfix, mir.Type.BasicType.F32_TYPE), castType(val, mir.Type.BasicType.F32_TYPE));
                    }
                    //return visitAddExpSuffix(perfix, addExpSuffix.getAddExpSuffix());
                    return perfix;
                }
                if (val instanceof Constant && addExpPerfix instanceof Constant) {
                    perfix = new Constant.ConstantInt((int) castConstantType((Constant) addExpPerfix, mir.Type.BasicType.I32_TYPE).getConstValue()
                            - (int) castConstantType((Constant) val, mir.Type.BasicType.I32_TYPE).getConstValue());
                } else {
                    perfix = new Instruction.BinaryOperation.Sub(currentBB, mir.Type.BasicType.I32_TYPE, castType(addExpPerfix, mir.Type.BasicType.I32_TYPE), castType(val, mir.Type.BasicType.I32_TYPE));
                }
                //return visitAddExpSuffix(perfix, addExpSuffix.getAddExpSuffix());
                return perfix;
            }
            default -> throw new SemanticError("Bad Add Op of AddExp");
        }
    }

    private Value visitMulExpSuffix(Value mulExpPerfix, Ast.MulExpSuffix mulExpSuffix) throws SemanticError {
        if (!mulExpSuffix.hasUnary()) {
            return mulExpPerfix;
        }
        switch (mulExpSuffix.getMulOp().type) {
            case MUL -> {
                Value val = visitUnaryExp(mulExpSuffix.getUnaryExp());
                Value perfix;
                if (val instanceof Constant && ((Constant) val).isZero()) {
                    assert val.getType() instanceof Type.BasicType;
                    return castConstantType(new Constant.ConstantInt(0), (Type.BasicType) val.getType());
                }
                if (mulExpPerfix instanceof Constant && ((Constant) mulExpPerfix).isZero()) {
                    assert mulExpPerfix.getType() instanceof Type.BasicType;
                    return castConstantType(new Constant.ConstantInt(0), (Type.BasicType) mulExpPerfix.getType());
                }
                if (val.getType() == mir.Type.BasicType.F32_TYPE || mulExpPerfix.getType() == mir.Type.BasicType.F32_TYPE) {
                    if (val instanceof Constant && mulExpPerfix instanceof Constant) {
                        perfix = new Constant.ConstantFloat((float) castConstantType((Constant) val, mir.Type.BasicType.F32_TYPE).getConstValue()
                                * (float) castConstantType((Constant) mulExpPerfix, mir.Type.BasicType.F32_TYPE).getConstValue());
                    } else {
                        perfix = new Instruction.BinaryOperation.FMul(currentBB, mir.Type.BasicType.F32_TYPE, castType(mulExpPerfix, mir.Type.BasicType.F32_TYPE), castType(val, mir.Type.BasicType.F32_TYPE));
                    }
                    return perfix;
                }
                if (val instanceof Constant && mulExpPerfix instanceof Constant) {
                    perfix = new Constant.ConstantInt((int) castConstantType((Constant) val, mir.Type.BasicType.I32_TYPE).getConstValue()
                            * (int) castConstantType((Constant) mulExpPerfix, mir.Type.BasicType.I32_TYPE).getConstValue());
                } else {
                    perfix = new Instruction.BinaryOperation.Mul(currentBB, mir.Type.BasicType.I32_TYPE, castType(mulExpPerfix, mir.Type.BasicType.I32_TYPE), castType(val, mir.Type.BasicType.I32_TYPE));
                }
                return perfix;
            }
            case DIV -> {
                Value val = visitUnaryExp(mulExpSuffix.getUnaryExp());
                Value perfix;
                if (val.getType() == mir.Type.BasicType.F32_TYPE || mulExpPerfix.getType() == mir.Type.BasicType.F32_TYPE) {
                    if (val instanceof Constant && mulExpPerfix instanceof Constant) {
                        perfix = new Constant.ConstantFloat((float) castConstantType((Constant) mulExpPerfix, mir.Type.BasicType.F32_TYPE).getConstValue()
                                / (float) castConstantType((Constant) val, mir.Type.BasicType.F32_TYPE).getConstValue());
                    } else {
                        perfix = new Instruction.BinaryOperation.FDiv(currentBB, mir.Type.BasicType.F32_TYPE, castType(mulExpPerfix, mir.Type.BasicType.F32_TYPE), castType(val, mir.Type.BasicType.F32_TYPE));
                    }
                    return perfix;
                }
                if (val instanceof Constant && mulExpPerfix instanceof Constant) {
                    perfix = new Constant.ConstantInt((int) castConstantType((Constant) mulExpPerfix, mir.Type.BasicType.I32_TYPE).getConstValue()
                            / (int) castConstantType((Constant) val, mir.Type.BasicType.I32_TYPE).getConstValue());
                } else {
                    perfix = new Instruction.BinaryOperation.Div(currentBB, mir.Type.BasicType.I32_TYPE, castType(mulExpPerfix, mir.Type.BasicType.I32_TYPE), castType(val, mir.Type.BasicType.I32_TYPE));
                }
                return perfix;
            }
            case MOD -> {
                Value val = visitUnaryExp(mulExpSuffix.getUnaryExp());
                Value perfix;
                if (val.getType() == mir.Type.BasicType.F32_TYPE || mulExpPerfix.getType() == mir.Type.BasicType.F32_TYPE) {
                    if (val instanceof Constant && mulExpPerfix instanceof Constant) {
                        perfix = new Constant.ConstantFloat((float) castConstantType((Constant) mulExpPerfix, mir.Type.BasicType.F32_TYPE).getConstValue()
                                % (float) castConstantType((Constant) val, mir.Type.BasicType.F32_TYPE).getConstValue());
                    } else {
                        perfix = new Instruction.BinaryOperation.FRem(currentBB, mir.Type.BasicType.F32_TYPE, castType(mulExpPerfix, mir.Type.BasicType.F32_TYPE), castType(val, mir.Type.BasicType.F32_TYPE));
                    }
                    return perfix;
                }
                if (val instanceof Constant && mulExpPerfix instanceof Constant) {
                    perfix = new Constant.ConstantInt((int) castConstantType((Constant) mulExpPerfix, mir.Type.BasicType.I32_TYPE).getConstValue()
                            % (int) castConstantType((Constant) val, mir.Type.BasicType.I32_TYPE).getConstValue());
                } else {
                    perfix = new Instruction.BinaryOperation.Rem(currentBB, mir.Type.BasicType.I32_TYPE, castType(mulExpPerfix, mir.Type.BasicType.I32_TYPE), castType(val, mir.Type.BasicType.I32_TYPE));
                }
                return perfix;
            }
            default -> throw new SemanticError("Bad Mul Op of MulExp");
        }
    }

    private Value visitUnaryExp(Ast.UnaryExp exp) throws SemanticError {
        if (exp.isPrimaryExp()) {
            return visitPrimaryExp(exp.getPrimaryExp());
        } else if (exp.isFunctionCall()) {
            return visitFunctionCall(exp.getIdent(), exp.getFuncRParams(), exp.getSTR());
        } else if (exp.isSubUnaryExp()) {
            Value ret = visitUnaryExp(exp.getUnaryExp());
            if (exp.getUnaryOp().type == Token.Type.ADD) {
                return ret;
            } else if (exp.getUnaryOp().type == Token.Type.SUB) {
                if (ret instanceof Constant) {
                    if (ret.getType() == mir.Type.BasicType.F32_TYPE) {
                        return new Constant.ConstantFloat(-(float) ((Constant) ret).getConstValue());
                    } else if (ret.getType() == mir.Type.BasicType.I32_TYPE) {
                        return new Constant.ConstantInt(-(int) ((Constant) ret).getConstValue());
                    } else if (ret.getType() == mir.Type.BasicType.I1_TYPE) {
                        return new Constant.ConstantInt(-(int) ((Constant) ret).getConstValue());
                    } else {
                        throw new SemanticError("Bad Operand of Unary Exp");
                    }
                }
                if (ret.getType() == mir.Type.BasicType.F32_TYPE) {
                    return new Instruction.BinaryOperation.FSub(currentBB, mir.Type.BasicType.F32_TYPE, new Constant.ConstantFloat(0), ret);
                } else if (ret.getType() == mir.Type.BasicType.I32_TYPE) {
                    return new Instruction.BinaryOperation.Sub(currentBB, mir.Type.BasicType.I32_TYPE, new Constant.ConstantInt(0), ret);
                } else if (ret.getType() == mir.Type.BasicType.I1_TYPE) {
                    return new Instruction.BinaryOperation.Sub(currentBB, mir.Type.BasicType.I1_TYPE, new Constant.ConstantInt(0), castType(ret, mir.Type.BasicType.I32_TYPE));
                } else {
                    throw new SemanticError("Bad Operand of Unary Exp");
                }
            } else if (exp.getUnaryOp().type == Token.Type.NOT) {
                if (ret instanceof Constant) {
                    if (ret.getType() == mir.Type.BasicType.F32_TYPE) {
                        return new Constant.ConstantBool((float) ((Constant) ret).getConstValue() == 0 ? 1 : 0);
                    } else if (ret.getType() == mir.Type.BasicType.I32_TYPE) {
                        return new Constant.ConstantBool((int) ((Constant) ret).getConstValue() == 0 ? 1 : 0);
                    } else if (ret.getType() == mir.Type.BasicType.I1_TYPE) {
                        return new Constant.ConstantBool((int) ((Constant) ret).getConstValue() == 0 ? 1 : 0);
                    } else {
                        throw new SemanticError("Bad Operand of Unary Exp");
                    }
                }
                if (ret.getType() == mir.Type.BasicType.F32_TYPE) {
                    return new Instruction.Fcmp(currentBB, Instruction.Fcmp.CondCode.EQ, ret, new Constant.ConstantFloat(0));
                } else if (ret.getType() == mir.Type.BasicType.I32_TYPE) {
                    return new Instruction.Icmp(currentBB, Instruction.Icmp.CondCode.EQ, ret, new Constant.ConstantInt(0));
                } else if (ret.getType() == mir.Type.BasicType.I1_TYPE) {
                    return new Instruction.Icmp(currentBB, Instruction.Icmp.CondCode.EQ, castType(ret, mir.Type.BasicType.I32_TYPE), new Constant.ConstantInt(0));
                } else {
                    throw new SemanticError("Bad Operand of Unary Exp");
                }
            } else {
                throw new SemanticError("Bad Op of Unary Exp");
            }
        } else {
            throw new SemanticError("Not a Unary Exp");
        }
    }

    private Value visitFunctionCall(Ast.Ident ident, Ast.FuncRParams funcRParams, Token str) throws SemanticError {
        Function function = manager.getFunctions().get(ident.identifier.content);
        if (function == null) {
            function = Manager.ExternFunc.externFunctions.get(ident.identifier.content);
            if (function == null) {
                // System.err.println("Undefined Function: " + ident.identifier.content);
                setUndef(ident);
            } else {
                manager.addFunction(function);
            }
        }
        ArrayList<Value> rParams = new ArrayList<>();

        if (str != null) {
            assert ident.identifier.content.equals(Manager.ExternFunc.PRINTF.getName());
            for (int i = 0; i < funcRParams.getParams().size(); i++) {
                rParams.add(visitExp(funcRParams.getParams().get(i)));
            }
            globalStr.add(str.content);
            // 识别str 中 %d 的个数
            Pattern pattern = Pattern.compile("%d");
            Matcher matcher = pattern.matcher(str.content);

            int count = 0;
            while (matcher.find()) {
                count++;
            }
            // 检查个数
            if(count != funcRParams.getParams().size()) {
                manager.addNumberedError(new NumberedError(manager.astRecorder.get(ident), 'l'));
                throw new SemanticError("Wrong number of parameters: " + ident.identifier.content);
            }

            return new Instruction.Call(currentBB, function, rParams, globalStr.size());
        }


        if (function.getArgumentsTP().size() != funcRParams.getParams().size()) {
            manager.addNumberedError(new NumberedError(manager.astRecorder.get(ident), 'd'));
            throw new SemanticError("Wrong number of parameters: " + ident.identifier.content);
        }

        ArrayList<Type> fParams = function.getArgumentsTP();
        for (int i = 0; i < funcRParams.getParams().size(); i++) {
            // check if the type of parameter is correct
            if (!fParams.get(i).equals(visitExp(funcRParams.getParams().get(i)).getType())) {
                manager.addNumberedError(new NumberedError(manager.astRecorder.get(ident), 'e'));
                throw new SemanticError("Wrong type of parameters: " + ident.identifier.content);
            }
            if (fParams.get(i) instanceof Type.BasicType)
                rParams.add(castType(visitExp(funcRParams.getParams().get(i)), (Type.BasicType) fParams.get(i)));
            else
                rParams.add(visitExp(funcRParams.getParams().get(i)));
        }


        return new Instruction.Call(currentBB, function, rParams);
    }

    private Value visitPrimaryExp(Ast.PrimaryExp exp) throws SemanticError {
        if (exp.isExp()) {
            return visitExp(exp.getExp());
        } else if (exp.isLval()) {
            return visitLval(exp.getLval(), false);
        } else if (exp.isNumber()) {
            return visitNumber(exp.getNumber());
        } else {
            throw new SemanticError("Not a Primary Exp");
        }
    }

    private void setUndef(Ast.Ident ident) throws SemanticError {
        manager.addNumberedError(new NumberedError(ident.identifier.line, 'c'));
        throw new SemanticError("Undefined Variable: " + ident.identifier.content);
    }

    private Value visitLval(Ast.Lval lval, boolean getAddr) throws SemanticError {
        Ast.Ident ident = lval.getIdent();
        Symbol symbol = currentSymTable.getSymbol(ident, true);
        if (symbol == null) {
            setUndef(ident);
        }
        //如果是一个int或float类型的可以获得值的量
        if (canGetConstantVal(symbol) && (symbol.getType() instanceof Type.BasicType) && !getAddr) {
            assert symbol.getCurValue() instanceof InitValue.ValueInit;
            return ((InitValue.ValueInit) symbol.getCurValue()).getValue();
        }
        //否则获取变量的指针地址
        Value address = symbol.getAllocInst();
        if (!(address.getType() instanceof Type.PointerType)) {
            throw new SemanticError("Wrong type of the Pointer to " + ident.identifier.content + "Expected Pointer Type, But got" + address.getType());
        }
        Type contentType = ((Type.PointerType) address.getType()).getInnerType();
        if (contentType instanceof Type.BasicType && !getAddr) {
            return new Instruction.Load(currentBB, address);
        }

        //对于数组类型
        Value pointer = address;
        ArrayList<Value> offsets = new ArrayList<>();
        offsets.add(new Constant.ConstantInt(0));
        boolean hasOffSet = false;
        for (Ast.AddExp exp :
                lval.getExps()) {
            Value offset = castType(visitExp(exp), mir.Type.BasicType.I32_TYPE);
            hasOffSet = true;

            //数组作为参数的情况，即func(a[][1][2]);
            if (contentType instanceof Type.PointerType) {
                pointer = new Instruction.Load(currentBB, pointer);
                contentType = ((Type.PointerType) contentType).getInnerType();
                offsets.clear();
                offsets.add(offset);
            } else if (contentType instanceof Type.ArrayType) {
                contentType = ((Type.ArrayType) contentType).getEleType();
                offsets.add(offset);
                //pointer = new Instruction.GetElementPtr(currentBB, pointer, contentType, offset);
            } else {
                throw new AssertionError("Fall on visitLval: " + ident.identifier.content);
            }
        }

        if (hasOffSet) {
//            pointer = new Instruction.GetElementPtr(currentBB, pointer, contentType, offsets);
            ArrayList flattenOffsets = flatOffsets(offsets, pointer, contentType);
            pointer = new Instruction.GetElementPtr(currentBB, pointer, contentType, flattenOffsets);
        }


        if (getAddr) {
            // 如果为常量或 pointer 为指向数组的指针 ，不能取地址
            if(symbol.isConstant() || ((Type.PointerType) pointer.getType()).getInnerType() instanceof Type.ArrayType ) {
                manager.addNumberedError(new NumberedError(manager.astRecorder.get(ident), 'h'));
                throw new SemanticError("Can not get address of a constant variable: " + ident.identifier.content);
            }
            return pointer;
        }

        if (contentType instanceof Type.BasicType || contentType instanceof Type.PointerType) {
            return new Instruction.Load(currentBB, pointer);
        } else if (contentType instanceof Type.ArrayType) {
            //返回数组的首地址
            ArrayList<Value> zeros = new ArrayList<>();
            zeros.add(new Constant.ConstantInt(0));
            zeros.add(new Constant.ConstantInt(0));
            return new Instruction.GetElementPtr(currentBB, pointer, ((Type.ArrayType) contentType).getEleType(), zeros);
        } else {
            throw new AssertionError("Wrong Type of visitLval ret");
        }

    }

    private ArrayList<Value> flatOffsets(ArrayList<Value> offsets, Value pointer, Type eleTp) {
        ArrayList<Value> newOffsets = new ArrayList<>();
        for (int i = 0; i < offsets.size() - 1; i++) {
            newOffsets.add(new Constant.ConstantInt(0));
        }
        assert pointer.getType() instanceof Type.PointerType;
        Type contentTp = ((Type.PointerType) pointer.getType()).getInnerType();
        Value newOffset = new Constant.ConstantInt(0);
        for (Value offset :
                offsets) {
            if (contentTp == eleTp) {
                if (newOffset instanceof Constant && offset instanceof Constant) {
                    newOffset = new Constant.ConstantInt(((int) ((Constant) newOffset).getConstValue() + (int) ((Constant) offset).getConstValue()));
                } else {
                    newOffset = new Instruction.BinaryOperation.Add(currentBB, mir.Type.BasicType.I32_TYPE, newOffset, offset);
                }
            } else {
                assert contentTp instanceof Type.ArrayType;
                Value value;
                if (offset instanceof Constant) {
                    if (eleTp instanceof Type.ArrayType)
                        value = new Constant.ConstantInt((int) ((Constant) offset).getConstValue() * ((Type.ArrayType) contentTp).getFlattenSize() /
                                ((Type.ArrayType) eleTp).getFlattenSize());
                    else
                        value = new Constant.ConstantInt((int) ((Constant) offset).getConstValue() * ((Type.ArrayType) contentTp).getFlattenSize());
                } else {
                    if (eleTp instanceof Type.ArrayType)
                        value = new Instruction.BinaryOperation.Mul(currentBB, mir.Type.BasicType.I32_TYPE, offset,
                                new Constant.ConstantInt(((Type.ArrayType) contentTp).getFlattenSize() / ((Type.ArrayType) eleTp).getFlattenSize()));
                    else
                        value = new Instruction.BinaryOperation.Mul(currentBB, mir.Type.BasicType.I32_TYPE, offset, new Constant.ConstantInt(((Type.ArrayType) contentTp).getFlattenSize()));
                }
                if (value instanceof Constant && newOffset instanceof Constant) {
                    newOffset = new Constant.ConstantInt(((int) ((Constant) newOffset).getConstValue() + (int) ((Constant) value).getConstValue()));
                } else {
                    newOffset = new Instruction.BinaryOperation.Add(currentBB, mir.Type.BasicType.I32_TYPE, newOffset, value);
                }
                contentTp = ((Type.ArrayType) contentTp).getEleType();
            }
        }

        newOffsets.add(newOffset);
        return newOffsets;
    }

    private Value visitNumber(Token number) throws SemanticError {
        if (number.type == Token.Type.DEC_FLOAT || number.type == Token.Type.HEX_FLOAT) {
            if (number.type == Token.Type.HEX_FLOAT) {
                if (number.content.contains("0x") || number.content.contains("0X")) {
                    return new Constant.ConstantFloat(Float.intBitsToFloat(new BigInteger(number.content.substring(2).toUpperCase(), 16).intValue()));
                } else {
                    return new Constant.ConstantFloat(Float.intBitsToFloat(new BigInteger(number.content.toUpperCase(), 16).intValue()));
                }
            }
            return new Constant.ConstantFloat(Float.parseFloat(number.content));
        } else if (number.type == Token.Type.DEC_INT || number.type == Token.Type.HEX_INT || number.type == Token.Type.OCT_INT) {
            if (number.type == Token.Type.HEX_INT) {
                if (number.content.contains("0x") || number.content.contains("0X")) {
                    return new Constant.ConstantInt(Integer.parseInt(number.content.substring(2).toUpperCase(), 16));
                } else {
                    return new Constant.ConstantInt(Integer.parseInt(number.content.toUpperCase(), 16));
                }
            }
            if (number.type == Token.Type.OCT_INT) {
                return new Constant.ConstantInt(Integer.parseInt(number.content, 8));
            }
            return new Constant.ConstantInt(Integer.parseInt(number.content));
        } else {
            throw new SemanticError("NAN: " + number.content);
        }
    }

    private void visitVarDecl(Ast.VarDecl varDecl) throws SemanticError {
        for (Ast.VarDef varDef :
                varDecl.getVarDefs()) {
            visitVarDef(varDef, varDecl.getBtype());
        }
    }

    private void setDuplicatedDefine(Ast.Ident ident) throws SemanticError {
        manager.addNumberedError(new NumberedError(manager.astRecorder.get(ident), 'b'));
        throw new SemanticError("Duplicated Define: " + ident.identifier.content);
    }

    private void checkDuplicatedDefine(Ast.Ident ident) throws SemanticError {
        if (currentSymTable.hasSymbol(ident, false)) {
            setDuplicatedDefine(ident);
        }
    }

    //基本仿照visitConstDef
    private void visitVarDef(Ast.VarDef varDef, Ast.Btype btype) throws SemanticError {
        Ast.Ident ident = varDef.getIdent();
        checkDuplicatedDefine(ident);
        Type defType = switch (btype.type.type) {
            case INT -> mir.Type.BasicType.I32_TYPE;
            case FLOAT -> mir.Type.BasicType.F32_TYPE;
            default -> throw new SemanticError("Wrong Type of variable" + btype.type.type);
        };
        //计算数组的维度
        Calculator calculator = new Calculator(currentSymTable);
        ArrayList<Integer> dims = new ArrayList<>();
        //数组类型
        for (Ast.AddExp addExp :
                varDef.getAddExps()) {
            dims.add(calculator.evalConsInt(addExp));
        }
        for (int i = dims.size() - 1; i >= 0; i--) {
            defType = new Type.ArrayType(dims.get(i), defType);
        }
        //初始化
        InitValue initValue;
        Ast.VarInitVal initVal = varDef.getInitVal();
        if (initVal.hasInitVal()) {
            if (defType.isInt32Ty() || defType.isFloatTy()) {
                if (initVal.getExp() == null) {
                    throw new SemanticError("Variable type could not be init by a list");
                }
                if (isGlobal) initValue = parseInitValue(defType, initVal.getExp());
                else initValue = parseInitExp(defType, initVal.getExp());
            } else {
                if (initVal.getInitVals() == null) {
                    throw new SemanticError("Array type could not be init by a single value");
                }
                initValue = parseInitArray(defType, initVal.getInitVals(), isGlobal);
            }
        } else {
            if (defType.isInt32Ty()) {
                initValue = new InitValue.ValueInit(new Constant.ConstantInt(0), mir.Type.BasicType.I32_TYPE);
            } else if (defType.isFloatTy()) {
                initValue = new InitValue.ValueInit(new Constant.ConstantFloat(0), mir.Type.BasicType.F32_TYPE);
            } else if (defType.isArrayTy()) {
                initValue = new InitValue.ZeroArrayInit(defType);
            } else {
                throw new SemanticError("Wrong define type");
            }
        }

        allocMem(defType, ident, initValue, false);
    }

    private void visitFuncDef(Ast.FuncDef funcDef) throws SemanticError {
        Token funcTypeToken = funcDef.getType();
        Type funcType = switch (funcTypeToken.type) {
            case INT -> mir.Type.BasicType.I32_TYPE;
            case FLOAT -> mir.Type.BasicType.F32_TYPE;
            case VOID -> mir.Type.VoidType.VOID_TYPE;
            default -> throw new SemanticError("Bad FuncType");
        };
        Ast.Ident ident = funcDef.getIdent();
        // check
        checkDuplicatedDefine(ident);
        if (manager.getFunctions().containsKey(ident.identifier.content)) {
            setDuplicatedDefine(ident);
        }
        if (!currentSymTable.hasSymbol(ident, false)) {
            currentSymTable.addSymbol(new Symbol(ident, funcType, null, false, null));
        }

        isGlobal = false;
        currentSymTable = new SymTable(currentSymTable);

        ArrayList<Type> argumentTPs = new ArrayList<>();
        for (Ast.FuncFParam funcFParam :
                funcDef.getFuncFParams()) {
            argumentTPs.add(parseFuncFParam(funcFParam));
        }

        Function thisFunc = new Function(funcType, ident.identifier.content, argumentTPs);
        currentFunc = thisFunc;
        currentBB = new BasicBlock(getBBName(), thisFunc);
        currentEntry = currentBB;

        ArrayList<Function.Argument> funcRParams = new ArrayList<>();

        for (int i = 0; i < argumentTPs.size(); i++) {
            Type argType = argumentTPs.get(i);
            Ast.Ident argIdent = funcDef.getFuncFParams().get(i).getIdent();

            Value ptr = new Instruction.Alloc(currentBB, argType);
            Function.Argument argument = new Function.Argument(argType, thisFunc);
            funcRParams.add(argument);
            argument.idx = i;
            new Instruction.Store(currentBB, argument, ptr);

            // check then add
            checkDuplicatedDefine(argIdent);
            currentSymTable.addSymbol(new Symbol(argIdent, argType, null, false, ptr));
        }

        thisFunc.setFuncRArguments(funcRParams);
        manager.addFunction(thisFunc);

        visitBlock(funcDef.getBlock(), true);

        if (!currentBB.isTerminated()) {
            // absence of returnStmt


            switch (funcTypeToken.type) {
                case VOID -> new Instruction.Return(currentBB);
                case FLOAT -> new Instruction.Return(currentBB, new Constant.ConstantFloat(0));
                case INT -> new Instruction.Return(currentBB, new Constant.ConstantInt(0));
                default -> throw new SemanticError("Bad FuncType");
            }
            if(!funcType.isVoidTy()) {
                manager.addNumberedError(new NumberedError(manager.funcBoundaryRecorder.get(ident), 'g'));
                throw new SemanticError("Absence of ReturnStmt");
            }

        }

        currentFunc = null;
        currentEntry = null;
        currentBB = null;
        isGlobal = true;
        countOfBB = 0;
        currentSymTable = currentSymTable.getParent();

    }

    private void visitBlock(Ast.Block block, boolean fromFunc) throws SemanticError {
        if (!fromFunc) {
            currentSymTable = new SymTable(currentSymTable);
        }
        for (Ast.BlockItem blockItem :
                block.getBlockItems()) {
            visitBlockItem(blockItem);
        }
        if (!fromFunc) {
            currentSymTable = currentSymTable.getParent();
        }
    }

    private void visitBlockItem(Ast.BlockItem blockItem) throws SemanticError {
        if (blockItem instanceof Ast.Decl) {
            visitDecl((Ast.Decl) blockItem);
        } else if (blockItem instanceof Ast.Stmt) {
            visitStmt((Ast.Stmt) blockItem);
        } else {
            throw new SemanticError("Bad BlockItem");
        }
    }

    private void visitStmt(Ast.Stmt stmt) {
        try {
            if (stmt instanceof Ast.AssignStmt) {
                visitAssignStmt((Ast.AssignStmt) stmt);
            } else if (stmt instanceof Ast.ExpStmt) {
                visitExpStmt((Ast.ExpStmt) stmt);
            } else if (stmt instanceof Ast.BlockStmt) {
                visitBlockStmt((Ast.BlockStmt) stmt);
            } else if (stmt instanceof Ast.IfStmt) {
                visitIfStmt((Ast.IfStmt) stmt);
            } else if (stmt instanceof Ast.ForStmt) {
                visitForStmt((Ast.ForStmt) stmt);
            } else if (stmt instanceof Ast.WhileStmt) {
                visitWhileStmt((Ast.WhileStmt) stmt);
            } else if (stmt instanceof Ast.IfElStmt) {
                visitIfElStmt((Ast.IfElStmt) stmt);
            } else if (stmt instanceof Ast.VoidStmt) {
                visitVoidStmt();
            } else if (stmt instanceof Ast.BreakStmt) {
                visitBreakStmt(stmt);
            } else if (stmt instanceof Ast.ContinueStmt) {
                visitContinueStmt(stmt);
            } else if (stmt instanceof Ast.ReturnStmt) {
                visitReturnStmt((Ast.ReturnStmt) stmt);
            }
        } catch (SemanticError error) {
//            System.err.println(error.getMessage());
        }
    }

    private void visitVoidStmt() {

    }

    private void visitAssignStmt(Ast.AssignStmt assignStmt) throws SemanticError {
        Ast.Ident ident = assignStmt.getLval().getIdent();
        Symbol symbol = currentSymTable.getSymbol(ident, true);
        Value addr = visitLval(assignStmt.getLval(), true);
        assert addr.getType() instanceof Type.PointerType;
        Type eleType = ((Type.PointerType) addr.getType()).getInnerType();
        assert eleType instanceof Type.BasicType;

        Value val = visitExp(assignStmt.getExp());
        if (val instanceof Constant && !isInCond() && !isInLoop() && !globalSymTable.hasSymbol(assignStmt.getLval().getIdent(), false)) {
            symbol.setCurValue(new InitValue.ValueInit(castConstantType((Constant) val, (Type.BasicType) eleType), eleType));
        } else {
            symbol.isChanged = true;
        }
        new Instruction.Store(currentBB, castType(val, (Type.BasicType) eleType), addr);
    }


    private boolean canGetConstantVal(Symbol symbol) {
        if (symbol.isConstant()) {
            return true;
        }
        if (globalSymTable.hasSymbol(symbol.getName(), false)) {
            return false;
        }
        return (!symbol.isChanged && symbol.getCurValue() instanceof InitValue.ValueInit && !isInLoop());
    }

    private void visitExpStmt(Ast.ExpStmt expStmt) throws SemanticError {
        visitExp(expStmt.getExp());
    }

    private void visitBlockStmt(Ast.BlockStmt blockStmt) throws SemanticError {
        visitBlock(blockStmt.getBlock(), false);
    }

    private void visitIfStmt(Ast.IfStmt ifStmt) throws SemanticError {
        BasicBlock thenBlock = new BasicBlock(getBBName(), currentFunc);
        BasicBlock followBlock = new BasicBlock(getBBName(), currentFunc);

        conds.add(ifStmt.getCond());
        Value cond = visitCond(ifStmt.getCond(), thenBlock, followBlock);
        assert cond.getType().isInt1Ty();
        new Instruction.Branch(currentBB, cond, thenBlock, followBlock);

        currentBB = thenBlock;
        visitStmt(ifStmt.getStmt());
        new Instruction.Jump(currentBB, followBlock);

        if (cond instanceof Constant.ConstantBool) {
            if (((Constant.ConstantBool) cond).isZero()) {
                thenBlock.isDeleted = true;
            }
        }
        conds.pop();
        currentBB = followBlock;
    }

    private Value visitCond(Ast.Cond cond, BasicBlock thenBlock, BasicBlock followBlock) throws SemanticError {
        assert cond instanceof Ast.LOrExp;
        return visitLOrExp((Ast.LOrExp) cond, thenBlock, followBlock);
    }


    private Value visitLOrExp(Ast.LOrExp lOrExp, BasicBlock thenBlock, BasicBlock followBlock) throws SemanticError {
        Iterator<Ast.LAndExp> iter = lOrExp.getlAndExps().iterator();
        Ast.LAndExp lAndExp = iter.next();
        Constant.ConstantBool tmp = new Constant.ConstantBool(0);
        for (; iter.hasNext(); lAndExp = iter.next()) {
            BasicBlock nextCond = new BasicBlock(getBBName(), currentFunc);
            Value cond = visitLAndExp(lAndExp, nextCond);
            assert cond.getType().isInt1Ty();
            if (cond instanceof Constant.ConstantBool) {
                if (!((Constant.ConstantBool) cond).isZero() || !tmp.isZero()) {
                    tmp = new Constant.ConstantBool(1);
                }
            }
            new Instruction.Branch(currentBB, cond, thenBlock, nextCond);
            currentBB = nextCond;
        }
        Value ret = visitLAndExp(lAndExp, followBlock);
        assert ret.getType().isInt1Ty();
        return ret;
    }


    private Value visitLAndExp(Ast.LAndExp lAndExp, BasicBlock followBlock) throws SemanticError {
        Iterator<Ast.EqExp> iter = lAndExp.getEqExps().iterator();
        Ast.EqExp eqExp = iter.next();
        Constant.ConstantBool tmp = new Constant.ConstantBool(1);
        for (; iter.hasNext(); eqExp = iter.next()) {
            BasicBlock nextCond = new BasicBlock(getBBName(), currentFunc);
            Value cond = visitEqExp(eqExp);
            assert cond.getType().isInt1Ty();
            if (cond instanceof Constant.ConstantBool) {
                if (((Constant.ConstantBool) cond).isZero() || tmp.isZero()) {
                    tmp = new Constant.ConstantBool(0);
                }
            }
            new Instruction.Branch(currentBB, cond, nextCond, followBlock);
            currentBB = nextCond;
        }
        Value ret = visitEqExp(eqExp);
        assert ret.getType().isInt1Ty();
        return ret;
    }

    /**
     * @param eqExp eqExp
     * @return Int1
     * @throws SemanticError semanticError
     */
    private Value visitEqExp(Ast.EqExp eqExp) throws SemanticError {
        Iterator<Ast.RelExp> relExpIterator = eqExp.getRelExps().iterator();
        Iterator<Token> tokenIterator = eqExp.getEqOps().iterator();

        Value last = null;
        while (relExpIterator.hasNext()) {
            Ast.RelExp relExp = relExpIterator.next();
            Value val = visitRelExp(relExp);
            assert val.getType() instanceof Type.BasicType;
            if (last == null) {
                last = val;
            } else {
                if (last.getType().isFloatTy() || val.getType().isFloatTy()) {
                    if (last instanceof Constant && val instanceof Constant) {
                        last = switch (tokenIterator.next().type) {
                            case EQ ->
                                    new Constant.ConstantBool((float) castConstantType((Constant) last, mir.Type.BasicType.F32_TYPE).getConstValue()
                                            == (float) castConstantType((Constant) val, mir.Type.BasicType.F32_TYPE).getConstValue() ? 1 : 0);
                            case NE ->
                                    new Constant.ConstantBool((float) castConstantType((Constant) last, mir.Type.BasicType.F32_TYPE).getConstValue()
                                            != (float) castConstantType((Constant) val, mir.Type.BasicType.F32_TYPE).getConstValue() ? 1 : 0);
                            default -> throw new SemanticError("Bad EqOp");
                        };
                    } else {
                        Instruction.Fcmp.CondCode condCode = switch (tokenIterator.next().type) {
                            case EQ -> Instruction.Fcmp.CondCode.EQ;
                            case NE -> Instruction.Fcmp.CondCode.NE;
                            default -> throw new SemanticError("Bad EqOp");
                        };
                        last = new Instruction.Fcmp(currentBB, condCode, castType(last, mir.Type.BasicType.F32_TYPE), castType(val, mir.Type.BasicType.F32_TYPE));
                    }
                } else {
                    if (last instanceof Constant && val instanceof Constant) {
                        last = switch (tokenIterator.next().type) {
                            case EQ ->
                                    new Constant.ConstantBool((int) castConstantType((Constant) last, mir.Type.BasicType.I32_TYPE).getConstValue()
                                            == (int) castConstantType((Constant) val, mir.Type.BasicType.I32_TYPE).getConstValue() ? 1 : 0);
                            case NE ->
                                    new Constant.ConstantBool((int) castConstantType((Constant) last, mir.Type.BasicType.I32_TYPE).getConstValue()
                                            != (int) castConstantType((Constant) val, mir.Type.BasicType.I32_TYPE).getConstValue() ? 1 : 0);
                            default -> throw new SemanticError("Bad EqOp");
                        };
                    } else {
                        Instruction.Icmp.CondCode condCode = switch (tokenIterator.next().type) {
                            case EQ -> Instruction.Icmp.CondCode.EQ;
                            case NE -> Instruction.Icmp.CondCode.NE;
                            default -> throw new SemanticError("Bad EqOp");
                        };
                        last = new Instruction.Icmp(currentBB, condCode, castType(last, (Type.BasicType) val.getType()), val);
                    }
                }
            }
        }
        return castType(last, mir.Type.BasicType.I1_TYPE);
    }

    /**
     * @param relExp relExp
     * @return BasicType val
     * @throws SemanticError semanticError
     */
    private Value visitRelExp(Ast.RelExp relExp) throws SemanticError {
        Iterator<Ast.AddExp> addExpIterator = relExp.getAddExps().iterator();
        Iterator<Token> relOpIterator = relExp.getRelOps().iterator();
        Value last = null;
        while (addExpIterator.hasNext()) {
            Ast.AddExp addExp = addExpIterator.next();
            Value val = visitExp(addExp);
            assert val.getType() instanceof Type.BasicType;
            if (last == null) {
                last = val;
            } else {
                if (last.getType().isFloatTy() || val.getType().isFloatTy()) {

                    if (last instanceof Constant && val instanceof Constant) {
                        last = switch (relOpIterator.next().type) {
                            case LT ->
                                    new Constant.ConstantBool((float) castConstantType((Constant) last, mir.Type.BasicType.F32_TYPE).getConstValue()
                                            < (float) castConstantType((Constant) val, mir.Type.BasicType.F32_TYPE).getConstValue() ? 1 : 0);
                            case LE ->
                                    new Constant.ConstantBool((float) castConstantType((Constant) last, mir.Type.BasicType.F32_TYPE).getConstValue()
                                            <= (float) castConstantType((Constant) val, mir.Type.BasicType.F32_TYPE).getConstValue() ? 1 : 0);
                            case GT ->
                                    new Constant.ConstantBool((float) castConstantType((Constant) last, mir.Type.BasicType.F32_TYPE).getConstValue()
                                            > (float) castConstantType((Constant) val, mir.Type.BasicType.F32_TYPE).getConstValue() ? 1 : 0);
                            case GE ->
                                    new Constant.ConstantBool((float) castConstantType((Constant) last, mir.Type.BasicType.F32_TYPE).getConstValue()
                                            >= (float) castConstantType((Constant) val, mir.Type.BasicType.F32_TYPE).getConstValue() ? 1 : 0);
                            default -> throw new SemanticError("Bad RelOp");
                        };
                    } else {
                        Instruction.Fcmp.CondCode condCode = switch (relOpIterator.next().type) {
                            case LT -> Instruction.Fcmp.CondCode.OLT;
                            case LE -> Instruction.Fcmp.CondCode.OLE;
                            case GT -> Instruction.Fcmp.CondCode.OGT;
                            case GE -> Instruction.Fcmp.CondCode.OGE;
                            default -> throw new SemanticError("Bad RelOp");
                        };
                        last = new Instruction.Fcmp(currentBB, condCode, castType(last, mir.Type.BasicType.F32_TYPE), castType(val, mir.Type.BasicType.F32_TYPE));
                    }
                } else {
                    if (last instanceof Constant && val instanceof Constant) {
                        last = switch (relOpIterator.next().type) {
                            case LT ->
                                    new Constant.ConstantBool((int) castConstantType((Constant) last, mir.Type.BasicType.I32_TYPE).getConstValue()
                                            < (int) castConstantType((Constant) val, mir.Type.BasicType.I32_TYPE).getConstValue() ? 1 : 0);
                            case LE ->
                                    new Constant.ConstantBool((int) castConstantType((Constant) last, mir.Type.BasicType.I32_TYPE).getConstValue()
                                            <= (int) castConstantType((Constant) val, mir.Type.BasicType.I32_TYPE).getConstValue() ? 1 : 0);
                            case GT ->
                                    new Constant.ConstantBool((int) castConstantType((Constant) last, mir.Type.BasicType.I32_TYPE).getConstValue()
                                            > (int) castConstantType((Constant) val, mir.Type.BasicType.I32_TYPE).getConstValue() ? 1 : 0);
                            case GE ->
                                    new Constant.ConstantBool((int) castConstantType((Constant) last, mir.Type.BasicType.I32_TYPE).getConstValue()
                                            >= (int) castConstantType((Constant) val, mir.Type.BasicType.I32_TYPE).getConstValue() ? 1 : 0);
                            default -> throw new SemanticError("Bad RelOp");
                        };
                    } else {
                        Instruction.Icmp.CondCode condCode = switch ((relOpIterator.next().type)) {
                            case LT -> Instruction.Icmp.CondCode.SLT;
                            case LE -> Instruction.Icmp.CondCode.SLE;
                            case GT -> Instruction.Icmp.CondCode.SGT;
                            case GE -> Instruction.Icmp.CondCode.SGE;
                            default -> throw new SemanticError("Bad RelOp");
                        };
                        last = new Instruction.Icmp(currentBB, condCode, castType(last, (Type.BasicType) val.getType()), val);
                    }
                }
            }
        }

        assert last.getType() instanceof Type.BasicType;
        return last;
    }

    private void visitWhileStmt(Ast.WhileStmt whileStmt) throws SemanticError {
        recorders.add(new Recorder());

        BasicBlock condBlock = new BasicBlock(getBBName(), currentFunc);
        BasicBlock whileBlock = new BasicBlock(getBBName(), currentFunc);
        BasicBlock followBlock = new BasicBlock(getBBName(), currentFunc);
        new Instruction.Jump(currentBB, condBlock);
        currentBB = condBlock;

        conds.add(whileStmt.getCond());
        Value cond = visitCond(whileStmt.getCond(), whileBlock, followBlock);
        new Instruction.Branch(currentBB, cond, whileBlock, followBlock);
        currentBB = whileBlock;
        visitStmt(whileStmt.getStmt());
        new Instruction.Jump(currentBB, condBlock);
        currentBB = followBlock;
        Recorder recoder = recorders.peek();
        for (Instruction.Jump jump :
                recoder.jumps) {
            switch (jump.getMark()) {
                case BREAK -> jump.backFill(followBlock);
                case CONTINUE -> jump.backFill(condBlock);
            }
        }
        conds.pop();
        recorders.pop();

    }

    private void visitForStmt(Ast.ForStmt stmt) throws SemanticError {

        recorders.add(new Recorder());

        BasicBlock initBB = new BasicBlock(getBBName(), currentFunc);
        BasicBlock condBB = new BasicBlock(getBBName(), currentFunc);
        BasicBlock bodyBB = new BasicBlock(getBBName(), currentFunc);
        BasicBlock followBB = new BasicBlock(getBBName(), currentFunc);
        new Instruction.Jump(currentBB, initBB);
        currentBB = initBB;
        if (stmt.getInit() != null) {
            visitAssignStmt((Ast.AssignStmt) stmt.getInit());
        }

        new Instruction.Jump(currentBB, condBB);
        currentBB = condBB;
        if (stmt.getCond() != null) {
            conds.add(stmt.getCond());
            Value cond = visitCond(stmt.getCond(), bodyBB, followBB);
            assert cond.getType().isInt1Ty();
            new Instruction.Branch(currentBB, cond, bodyBB, followBB);
        } else {
            new Instruction.Jump(currentBB, bodyBB);
        }

        currentBB = bodyBB;
        if (stmt.getStmt() != null) {
            visitStmt(stmt.getStmt());
        }
        if (stmt.getStep() != null) {
            visitAssignStmt((Ast.AssignStmt) stmt.getStep());
        }
        new Instruction.Jump(currentBB, condBB);

        currentBB = followBB;
        Recorder recoder = recorders.peek();
        for (Instruction.Jump jump :
                recoder.jumps) {
            switch (jump.getMark()) {
                case BREAK -> jump.backFill(followBB);
                case CONTINUE -> jump.backFill(condBB);
            }
        }
        if (stmt.getCond() != null) {
            conds.pop();
        }
        recorders.pop();
    }

    private void visitIfElStmt(Ast.IfElStmt ifElStmt) throws SemanticError {
        BasicBlock thenBlock = new BasicBlock(getBBName(), currentFunc);
        BasicBlock elseBlock = new BasicBlock(getBBName(), currentFunc);
        BasicBlock followBlock = new BasicBlock(getBBName(), currentFunc);

        conds.add(ifElStmt.getCond());
        Value cond = visitCond(ifElStmt.getCond(), thenBlock, elseBlock);
        assert cond.getType().isInt1Ty();
        new Instruction.Branch(currentBB, cond, thenBlock, elseBlock);


        currentBB = thenBlock;
        visitStmt(ifElStmt.getStmt());
        new Instruction.Jump(currentBB, followBlock);


        currentBB = elseBlock;
        visitStmt(ifElStmt.getStmt1());
        new Instruction.Jump(currentBB, followBlock);


        if (cond instanceof Constant.ConstantBool) {
            if (((Constant.ConstantBool) cond).isZero()) {
                thenBlock.isDeleted = true;
            } else {
                elseBlock.isDeleted = true;
            }
        }

        conds.pop();
        currentBB = followBlock;
    }

    private void visitBreakStmt(Ast.Stmt stmt) throws SemanticError {
        if (recorders.isEmpty()) {
            manager.addNumberedError(new NumberedError(manager.astRecorder.get(stmt), 'm'));
            throw new SemanticError("Break statement need a loop");
        }
        recorders.peek().record(Recorder.Mark.BREAK, new Instruction.Jump(currentBB, Recorder.Mark.BREAK));
    }

    private void visitContinueStmt(Ast.Stmt stmt) throws SemanticError {
        if (recorders.isEmpty()) {
            manager.addNumberedError(new NumberedError(manager.astRecorder.get(stmt), 'm'));
            throw new SemanticError("Continue statement need a loop");
        }
        recorders.peek().record(Recorder.Mark.CONTINUE, new Instruction.Jump(currentBB, Recorder.Mark.CONTINUE));
    }

    private void visitReturnStmt(Ast.ReturnStmt returnStmt) throws SemanticError {
        // check if this function do need returnStmt
        if (currentFunc.getRetType().isVoidTy()) {
            if (returnStmt.getExp() != null) {
                manager.addNumberedError(new NumberedError(manager.astRecorder.get(returnStmt), 'f'));
                throw new SemanticError("Void function should not return a value");
            }
        }

        Ast.AddExp exp = returnStmt.getExp();
        if (exp == null) {
            new Instruction.Return(currentBB);
        } else {
            new Instruction.Return(currentBB, castType(visitExp(exp), (Type.BasicType) currentFunc.getRetType()));
        }
    }


    private Type parseFuncFParam(Ast.FuncFParam funcFParam) throws SemanticError {
        Type retType = switch (funcFParam.getBtype().type.type) {
            case INT -> mir.Type.BasicType.I32_TYPE;
            case FLOAT -> mir.Type.BasicType.F32_TYPE;
            default -> throw new SemanticError("Bad Type of funcFParam");
        };

        ArrayList<Value> dims = new ArrayList<>();

        for (Ast.VarSuffix varSuffix :
                funcFParam.getVarSuffixes()) {
            if (varSuffix.isOmitExp()) {
                dims.add(new Value(mir.Type.VoidType.VOID_TYPE));
            } else {
                Calculator calculator = new Calculator(currentSymTable);
                dims.add(new Constant.ConstantInt(calculator.evalConsInt(varSuffix.getExp())));
            }
        }

        for (int i = dims.size() - 1; i > 0; i--) {
            if (dims.get(i).getType() == mir.Type.VoidType.VOID_TYPE) {
                throw new SemanticError("Only the first dimension can be omitted");
            } else {
                assert dims.get(i) instanceof Constant.ConstantInt;
                int size = (int) ((Constant.ConstantInt) dims.get(i)).getConstValue();
                if (size < 0) {
                    throw new SemanticError("Negative array dimension: " + size);
                }
                retType = new Type.ArrayType(size, retType);
            }
        }
        if (dims.isEmpty()) {
            return retType;
        }

        return new Type.PointerType(retType);
    }

    private String getBBName() {
        String name = currentFunc.getName() + "_BB" + countOfBB;
        countOfBB++;
        return name;
    }

    public Visitor() {
    }
}
