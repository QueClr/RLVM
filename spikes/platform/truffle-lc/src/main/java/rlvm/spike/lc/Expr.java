package rlvm.spike.lc;

/**
 * Resolved intermediate representation shared by the three back-ends (plain Java tree walker,
 * Truffle AST interpreter, Truffle Bytecode DSL interpreter). Variables are already lexically
 * addressed and closures are flat ("closure converted"): a lambda lists the expressions (evaluated in
 * the enclosing scope) whose values it captures.
 */
public sealed interface Expr {
    record Lit(long value) implements Expr {}

    /** Parameter {@code index} of the enclosing lambda. */
    record Arg(int index) implements Expr {}

    /** {@code let}-bound local in frame slot {@code slot} of the enclosing lambda. */
    record Local(int slot) implements Expr {}

    /** Captured variable {@code index} of the enclosing closure. */
    record Cap(int index) implements Expr {}

    record GlobalRef(Global global) implements Expr {}

    record Lam(String name, int arity, int numLocals, Expr[] captures, Expr body) implements Expr {}

    record App(Expr fn, Expr[] args) implements Expr {}

    /** op is one of + - * < = */
    record Prim(char op, Expr left, Expr right) implements Expr {}

    record If(Expr cond, Expr then, Expr otherwise) implements Expr {}

    record Let(int slot, Expr init, Expr body) implements Expr {}

    /** A whole program: global definitions (in order) followed by a result expression. */
    record Program(Global[] globals, Lam[] inits, Lam main) {}
}
