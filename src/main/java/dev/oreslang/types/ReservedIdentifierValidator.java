package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.List;
import java.util.Set;

/**
 * Defense-in-depth validation for globally reserved identifiers.
 *
 * The lexer already guarantees that source text cannot turn these keywords into
 * IDENT tokens. This validator preserves the same invariant for ASTs created by
 * Java tooling, incremental compiler caches, or future serialized IR readers.
 */
final class ReservedIdentifierValidator {
    private static final Set<String> RESERVED = Set.of(
            "of", "is", "as", "stop", "do", "done", "actor", "isolate", "recover", "panic");

    private ReservedIdentifierValidator() { }

    static void check(Ast.Program program) {
        if (program.namespace() != null) name(program.namespace(), "namespace");

        for (Ast.ImportDecl imported : program.imports()) {
            for (String importedName : imported.names()) {
                if (imported.kind() == Ast.ImportKind.FUNCTION && isCallableOnlyReserved(importedName)) continue;
                name(importedName, "imported name");
            }
            if (imported.namespace() != null) name(imported.namespace(), "import alias");
        }

        for (Ast.ModuleDecl module : program.modules()) module(module);
    }

    private static void module(Ast.ModuleDecl module) {
        name(module.name(), "module");
        annotations(module.annotations());
        for (Ast.Decl decl : module.declarations()) declaration(decl);
    }

    private static void declaration(Ast.Decl decl) {
        if (decl instanceof Ast.FunctionDecl fn) {
            if (!isCallableOnlyReserved(fn.name())) name(fn.name(), "callable");
            names(fn.genericParameters(), "generic parameter");
            parameters(fn.parameters());
            type(fn.returnType());
            annotations(fn.annotations());
            statements(fn.body());
            return;
        }

        if (decl instanceof Ast.ClassDecl klass) {
            name(klass.name(), "class");
            names(klass.genericParameters(), "generic parameter");
            types(klass.parents());
            types(klass.interfaces());
            for (Ast.FieldDecl field : klass.fields()) field(field);
            for (Ast.MethodDecl method : klass.methods()) method(method);
            return;
        }

        if (decl instanceof Ast.InterfaceDecl iface) {
            name(iface.name(), "interface");
            names(iface.genericParameters(), "generic parameter");
            types(iface.parents());
            for (Ast.InterfaceMember member : iface.members()) {
                if (member instanceof Ast.InterfaceFunctionDecl fn) {
                    name(fn.name(), "interface function");
                    names(fn.genericParameters(), "generic parameter");
                    parameters(fn.parameters());
                    type(fn.returnType());
                } else if (member instanceof Ast.InterfaceFieldDecl field) {
                    name(field.name(), "interface field");
                    type(field.type());
                }
            }
            return;
        }

        if (decl instanceof Ast.FieldDecl field) {
            field(field);
            return;
        }

        if (decl instanceof Ast.TypeAliasDecl alias) {
            name(alias.name(), "type alias");
            names(alias.genericParameters(), "generic parameter");
            type(alias.target());
        }
    }

    private static void field(Ast.FieldDecl field) {
        name(field.name(), "field");
        if (field.type() != null) type(field.type());
        if (field.initializer() != null) expression(field.initializer());
    }

    private static void method(Ast.MethodDecl method) {
        name(method.name(), "method");
        if (method.explicitReceiverType() != null) type(method.explicitReceiverType());
        names(method.genericParameters(), "generic parameter");
        parameters(method.parameters());
        type(method.returnType());
        annotations(method.annotations());
        statements(method.body());
    }

    private static void annotations(List<Ast.Annotation> annotations) {
        for (Ast.Annotation annotation : annotations) {
            name(annotation.name(), "annotation");
            types(annotation.arguments());
        }
    }

    private static void parameters(List<Ast.Param> parameters) {
        for (Ast.Param param : parameters) {
            name(param.name(), "parameter");
            type(param.type());
        }
    }

    private static void statements(List<Ast.Stmt> statements) {
        if (statements == null) return;
        for (Ast.Stmt stmt : statements) statement(stmt);
    }

    private static void statement(Ast.Stmt stmt) {
        if (stmt instanceof Ast.BindingStmt binding) {
            name(binding.name(), "binding");
            if (binding.declaredType() != null) type(binding.declaredType());
            expression(binding.initializer());
        } else if (stmt instanceof Ast.DestructureStmt destructure) {
            for (Ast.DestructureBinding binding : destructure.bindings()) {
                name(binding.name(), "destructure binding");
            }
            expression(destructure.initializer());
        } else if (stmt instanceof Ast.ReturnStmt ret) {
            if (ret.value() != null) expression(ret.value());
        } else if (stmt instanceof Ast.ExprStmt expr) {
            expression(expr.expression());
        } else if (stmt instanceof Ast.DeferStmt defer) {
            expression(defer.expression());
        } else if (stmt instanceof Ast.RecoverStmt recover) {
            expression(recover.handler());
        } else if (stmt instanceof Ast.PanicStmt panic) {
            expression(panic.value());
        } else if (stmt instanceof Ast.IfStmt conditional) {
            for (Ast.IfBranch branch : conditional.branches()) {
                expression(branch.condition());
                statements(branch.body());
            }
            statements(conditional.elseBody());
        } else if (stmt instanceof Ast.TryStmt attempted) {
            name(attempted.errorName(), "catch binding");
            statements(attempted.body());
            statements(attempted.catchBody());
            statements(attempted.finallyBody());
        } else if (stmt instanceof Ast.ForOfStmt loop) {
            name(loop.bindingName(), "for-of binding");
            expression(loop.iterable());
            statements(loop.body());
        } else if (stmt instanceof Ast.ForStmt loop) {
            if (loop.initializer() != null) statement(loop.initializer());
            if (loop.condition() != null) expression(loop.condition());
            if (loop.update() != null) expression(loop.update());
            statements(loop.body());
        }
    }

    private static void expression(Ast.Expr expr) {
        if (expr == null) return;

        if (expr instanceof Ast.NameExpr named) {
            name(named.name(), "name");
        } else if (expr instanceof Ast.BinaryExpr binary) {
            expression(binary.left());
            expression(binary.right());
        } else if (expr instanceof Ast.UnaryExpr unary) {
            expression(unary.operand());
        } else if (expr instanceof Ast.AssignExpr assignment) {
            expression(assignment.target());
            expression(assignment.value());
        } else if (expr instanceof Ast.ConditionalExpr conditional) {
            expression(conditional.condition());
            expression(conditional.whenTrue());
            expression(conditional.whenFalse());
        } else if (expr instanceof Ast.CallExpr call) {
            expression(call.callee());
            for (Ast.Expr argument : call.arguments()) expression(argument);
        } else if (expr instanceof Ast.MemberExpr member) {
            expression(member.receiver());
            name(member.member(), "member");
        } else if (expr instanceof Ast.IndexExpr index) {
            expression(index.receiver());
            expression(index.index());
        } else if (expr instanceof Ast.NewExpr created) {
            type(created.type());
            for (Ast.Expr argument : created.arguments()) expression(argument);
        } else if (expr instanceof Ast.AwaitExpr awaited) {
            expression(awaited.expression());
        } else if (expr instanceof Ast.ListExpr list) {
            for (Ast.Expr element : list.elements()) expression(element);
        } else if (expr instanceof Ast.TupleExpr tuple) {
            for (Ast.Expr element : tuple.elements()) expression(element);
        } else if (expr instanceof Ast.ObjectExpr object) {
            for (Ast.ObjectField field : object.fields()) {
                name(field.name(), "object field");
                expression(field.value());
            }
        } else if (expr instanceof Ast.LambdaExpr lambda) {
            parameters(lambda.parameters());
            if (lambda.expressionBody() != null) expression(lambda.expressionBody());
            statements(lambda.blockBody());
        } else if (expr instanceof Ast.ActorExpr actor) {
            parameters(actor.behavior().parameters());
            if (actor.behavior().expressionBody() != null) expression(actor.behavior().expressionBody());
            statements(actor.behavior().blockBody());
        }
    }

    private static void types(List<Ast.TypeRef> refs) {
        for (Ast.TypeRef ref : refs) type(ref);
    }

    private static void type(Ast.TypeRef ref) {
        if (ref == null) return;
        if (!ref.name().startsWith("$")) name(ref.name(), "type");
        for (Ast.TypeRef argument : ref.arguments()) type(argument);
    }

    private static void names(List<String> values, String context) {
        for (String value : values) name(value, context);
    }

    private static boolean isCallableOnlyReserved(String value) {
        return "stop".equals(value) || "do".equals(value) || "done".equals(value);
    }

    private static void name(String value, String context) {
        if (value == null) return;
        for (String part : value.split("\\.")) {
            if (RESERVED.contains(part)) {
                throw new IllegalArgumentException(
                        "reserved keyword '" + part + "' cannot be used as " + context + " identifier");
            }
        }
    }
}
