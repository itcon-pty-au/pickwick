import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Rewrites calls to Java 10 `URLDecoder.decode(String, Charset)` and
 * `URLEncoder.encode(String, Charset)` — API 33 on Android — to
 * `io.pickwick.app.compat.UrlCodecCompat`, in the app *and* every dependency.
 *
 * NewPipeExtractor's `Utils.decodeUrlUtf8` makes exactly this call, and neither
 * core library desugaring nor R8 backports it, so on Fire OS 6/7 (API 25/28)
 * every YouTube lookup dies with NoSuchMethodError. Rewriting at the call site
 * keeps the fix working across extractor upgrades instead of pinning a fork.
 *
 * The replacement has the identical descriptor, so the operand stack is
 * unchanged and no frame recomputation is needed.
 */
abstract class JavaApiBackports : AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor = object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
        override fun visitMethod(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor {
            val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
            return object : MethodVisitor(Opcodes.ASM9, mv) {
                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                    isInterface: Boolean,
                ) {
                    val shimmed = opcode == Opcodes.INVOKESTATIC &&
                        descriptor == CHARSET_DESCRIPTOR &&
                        ((owner == "java/net/URLDecoder" && name == "decode") ||
                            (owner == "java/net/URLEncoder" && name == "encode"))
                    if (shimmed) {
                        super.visitMethodInsn(opcode, SHIM, name, descriptor, false)
                    } else {
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    }
                }
            }
        }
    }

    // The shim itself calls the String-named overloads, so it can't loop, but
    // skipping it keeps the intent obvious.
    override fun isInstrumentable(classData: ClassData): Boolean =
        classData.className != SHIM.replace('/', '.')

    private companion object {
        const val SHIM = "io/pickwick/app/compat/UrlCodecCompat"
        const val CHARSET_DESCRIPTOR = "(Ljava/lang/String;Ljava/nio/charset/Charset;)Ljava/lang/String;"
    }
}
