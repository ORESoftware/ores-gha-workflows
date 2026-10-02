package dev.oreslang;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.nodes.OresEvalRootNode;
import dev.oreslang.nodes.OresInteropRootNode;
import dev.oreslang.runtime.OresContext;
import org.graalvm.polyglot.SandboxPolicy;

import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

@TruffleLanguage.Registration(
        id = OresLanguage.ID,
        name = "Oreslang",
        version = "0.1.0",
        defaultMimeType = OresLanguage.MIME_TYPE,
        characterMimeTypes = OresLanguage.MIME_TYPE,
        contextPolicy = TruffleLanguage.ContextPolicy.EXCLUSIVE,
        sandbox = SandboxPolicy.UNTRUSTED,
        website = "https://github.com/ores-truffle-oreslang/oreslang-source.java")
public final class OresLanguage extends TruffleLanguage<OresContext> {
    public static final String ID = "ores";
    public static final String MIME_TYPE = "application/x-oreslang";

    @Override
    protected OresContext createContext(Env env) {
        return new OresContext(this, env);
    }

    @Override
    protected void disposeContext(OresContext context) {
        context.close();
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        var source = request.getSource();
        String text = source.getCharacters().toString();
        Ast.Program program = OresCompiler.parseAndTypeCheck(text);
        String codeUnitId = source.getPath();
        if (codeUnitId != null && !codeUnitId.isBlank()) {
            codeUnitId = normalizePathIdentity(codeUnitId);
        } else if (source.getURI() != null
                && "file".equalsIgnoreCase(source.getURI().getScheme())) {
            try {
                codeUnitId = Path.of(source.getURI())
                        .toAbsolutePath()
                        .normalize()
                        .toString()
                        .replace('\\', '/');
            } catch (RuntimeException invalidPath) {
                throw new IllegalArgumentException("invalid Oreslang source URI identity", invalidPath);
            }
        } else {
            codeUnitId = normalizeLogicalIdentity(source.getName());
        }
        if (codeUnitId == null || codeUnitId.isBlank()) codeUnitId = "<anonymous>";
        RootCallTarget evaluator = new OresEvalRootNode(
                this,
                program,
                codeUnitId,
                sourceDigest(text)).getCallTarget();
        return new OresInteropRootNode(this, evaluator).getCallTarget();
    }

    private static String normalizeLogicalIdentity(String name) {
        if (name == null || name.isBlank()) return name;
        try {
            String normalized = Path.of(name.replace('\\', '/'))
                    .normalize()
                    .toString()
                    .replace('\\', '/');
            return normalized.isBlank() ? name : normalized;
        } catch (InvalidPathException invalidPath) {
            // Source names may be logical labels rather than filesystem paths.
            return name;
        }
    }

    private static String normalizePathIdentity(String path) {
        try {
            return Path.of(path.replace('\\', '/'))
                    .toAbsolutePath()
                    .normalize()
                    .toString()
                    .replace('\\', '/');
        } catch (InvalidPathException invalidPath) {
            throw new IllegalArgumentException("invalid Oreslang source path identity", invalidPath);
        }
    }

    private static String sourceDigest(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
