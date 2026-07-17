import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Artifact transform that rewrites GraphHopper's JDK-13 absolute-bulk ByteBuffer calls to a
 * compat shim, so prebuilt region graphs load on Android below API 34.
 *
 * GraphHopper 11's MMapDataAccess reads/writes graph segments with
 * `ByteBuffer.get(int, byte[], int, int)` / `put(int, byte[], int, int)` - added in Java 13,
 * present on ART only from API 34. On the API 26-33 devices Vela's minSdk still supports, every
 * offline graph load died with NoSuchMethodError. The only call sites in the whole dependency
 * tree are the six inside MMapDataAccess (verified by scanning every GraphHopper jar's constant
 * pool), so this transform patches exactly `graphhopper-core-*.jar`, and inside it exactly
 * `com/graphhopper/storage/MMapDataAccess.class`: each such call becomes an INVOKESTATIC to
 * `app.vela.core.util.ByteBufferCompat`, which does the same thing via duplicate()+position()
 * (API 1, and just as thread-safe - the duplicate carries its own position). Every other jar
 * passes through untouched.
 *
 * (Contributed by ars18 in the vela-dpad fork; ported here so offline routing works on all
 * supported devices, not just API 34+.)
 */
abstract class GraphHopperByteBufferPatch : TransformAction<TransformParameters.None> {

    @get:InputArtifact
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        if (!input.name.startsWith("graphhopper-core-")) {
            outputs.file(input) // pass through untouched
            return
        }
        val out = outputs.file(input.nameWithoutExtension + "-bbpatched.jar")
        ZipFile(input).use { zip ->
            ZipOutputStream(out.outputStream().buffered()).use { zos ->
                for (entry in zip.entries()) {
                    val bytes = zip.getInputStream(entry).readBytes()
                    val patched = if (entry.name == "com/graphhopper/storage/MMapDataAccess.class") {
                        patchClass(bytes)
                    } else {
                        bytes
                    }
                    zos.putNextEntry(ZipEntry(entry.name))
                    zos.write(patched)
                    zos.closeEntry()
                }
            }
        }
    }

    private fun patchClass(bytes: ByteArray): ByteArray {
        val reader = ClassReader(bytes)
        val writer = ClassWriter(reader, 0) // pure call-site swap: stack shape is unchanged
        reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
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
                        owner: String?,
                        mName: String?,
                        mDesc: String?,
                        isInterface: Boolean,
                    ) {
                        if (opcode == Opcodes.INVOKEVIRTUAL &&
                            owner == "java/nio/ByteBuffer" &&
                            (mName == "get" || mName == "put") &&
                            mDesc == "(I[BII)Ljava/nio/ByteBuffer;"
                        ) {
                            super.visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                "app/vela/core/util/ByteBufferCompat",
                                mName,
                                "(Ljava/nio/ByteBuffer;I[BII)Ljava/nio/ByteBuffer;",
                                false,
                            )
                        } else {
                            super.visitMethodInsn(opcode, owner, mName, mDesc, isInterface)
                        }
                    }
                }
            }
        }, 0)
        return writer.toByteArray()
    }
}
