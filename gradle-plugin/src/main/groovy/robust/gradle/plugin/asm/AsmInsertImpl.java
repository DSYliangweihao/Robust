package robust.gradle.plugin.asm;

import com.android.utils.AsmUtils;
import com.meituan.robust.ChangeQuickRedirect;
import com.meituan.robust.Constants;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipOutputStream;

import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.bytecode.AccessFlag;
import robust.gradle.plugin.InsertcodeStrategy;


/**
 * Created by zhangmeng on 2017/5/10.
 * <p>
 * insert code using asm
 */

public class AsmInsertImpl extends InsertcodeStrategy {


    public AsmInsertImpl(List<String> hotfixPackageList, List<String> hotfixMethodList, List<String> exceptPackageList, List<String> exceptMethodList, boolean isHotfixMethodLevel, boolean isExceptMethodLevel, boolean isForceInsertLambda) {
        super(hotfixPackageList, hotfixMethodList, exceptPackageList, exceptMethodList, isHotfixMethodLevel, isExceptMethodLevel, isForceInsertLambda);
    }

    @Override
    protected void insertCode(List<CtClass> box, File jarFile) throws IOException, CannotCompileException {
//        创建输出流
        ZipOutputStream outStream = new JarOutputStream(new FileOutputStream(jarFile));
        //get every class in the box ,ready to insert code
        for (CtClass ctClass : box) {
//            强制将class转化为public的形式
            //change modifier to public ,so all the class in the apk will be public ,you will be able to access it in the patch
            ctClass.setModifiers(AccessFlag.setPublic(ctClass.getModifiers()));
            if (isNeedInsertClass(ctClass.getName()) &&
                    !(ctClass.isInterface() || ctClass.getDeclaredMethods().length < 1)) {
                //only insert code into specific classes
                zipFile(transformCode(ctClass.toBytecode(),
//                        得到/这样形式的类名
                        ctClass.getName().replaceAll("\\.", "/")),
                        outStream,
//                        将所有的.转化为/ 然后添加后缀.class
                        ctClass.getName().replaceAll("\\.", "/") + ".class");
            } else {
                zipFile(ctClass.toBytecode(), outStream, ctClass.getName().replaceAll("\\.", "/") + ".class");

            }
            ctClass.defrost();
        }
        outStream.close();
    }

    private class InsertMethodBodyAdapter extends ClassVisitor implements Opcodes {

        public InsertMethodBodyAdapter() {
            super(Opcodes.ASM5);
        }

        ClassWriter classWriter;
        private String className;
        //this maybe change in the future
        private Map<String, Boolean> methodInstructionTypeMap;

        public InsertMethodBodyAdapter(ClassWriter cw, String className, Map<String, Boolean> methodInstructionTypeMap) {
            super(Opcodes.ASM5, cw);
            this.classWriter = cw;
            this.className = className;
            this.methodInstructionTypeMap = methodInstructionTypeMap;
            //insert the field 访问方法的类型
            classWriter.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, Constants.INSERT_FIELD_NAME, Type.getDescriptor(ChangeQuickRedirect.class), null, null);
        }
//https://docs.oracle.com/javase/specs/jvms/se9/html/jvms-4.html#jvms-4.4.7
//Signature_attribute {
//    u2 attribute_name_index;
//    u4 attribute_length;
//    u2 signature_index;
//}
        @Override
        public MethodVisitor visitMethod(int access, String name,
                                         String desc, String signature,
                                         String[] exceptions) {
            if (isProtect(access)) {
                access = setPublic(access);
            }
            MethodVisitor mv = super.visitMethod(access, name,
                    desc, signature, exceptions);
//   不是方法的指令的话就直接返回就行
            if (!isQualifiedMethod(access, name, desc, methodInstructionTypeMap)) {
                return mv;
            }
            StringBuilder parameters = new StringBuilder();
//            类型参数
            Type[] types = Type.getArgumentTypes(desc);
            for (Type type : types) {
                parameters.append(type.getClassName()).append(",");
            }
//            把最后的一个符号去掉
            //remove the last ","
            if (parameters.length() > 0 && parameters.charAt(parameters.length() - 1) == ',') {
                parameters.deleteCharAt(parameters.length() - 1);
            }
            //record method number
            methodMap.put(className.replace('/', '.') + "." + name + "(" + parameters.toString() + ")", insertMethodCount.incrementAndGet());
            return new MethodBodyInsertor(mv, className, desc,
                    isStatic(access), String.valueOf(insertMethodCount.get()),
                    name, access);
        }

        private boolean isProtect(int access) {
            return (access & Opcodes.ACC_PROTECTED) != 0;
        }

        private int setPublic(int access) {
            return (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
        }

        private boolean isQualifiedMethod(int access, String name, String desc, Map<String, Boolean> c) {
            //类初始化函数和构造函数过滤
            if (AsmUtils.CLASS_INITIALIZER.equals(name) ||
                    AsmUtils.CONSTRUCTOR.equals(name)) {
                return false;
            }
            //@warn 这部分代码请重点review一下，判断条件写错会要命
            //这部分代码请重点review一下，判断条件写错会要命
            // synthetic 方法暂时不aop 比如AsyncTask 会生成一些同名 synthetic方法,对synthetic 以及private的方法也插入的代码，主要是针对lambda表达式
            if (!isForceInsertLambda && ((access & Opcodes.ACC_SYNTHETIC) != 0) && ((access & Opcodes.ACC_PRIVATE) == 0)) {
                return false;
            }


//            抽象
            if ((access & Opcodes.ACC_ABSTRACT) != 0) {
                return false;
            }
//            本地方法
            if ((access & Opcodes.ACC_NATIVE) != 0) {
                return false;
            }
//            接口
            if ((access & Opcodes.ACC_INTERFACE) != 0) {
                return false;
            }
//过时的
            if ((access & Opcodes.ACC_DEPRECATED) != 0) {
                return false;
            }

            //方法过滤
            if (isExceptMethodLevel && exceptMethodList != null) {
                for (String item : exceptMethodList) {
                    if (name.matches(item)) {
                        return false;
                    }
                }
            }
//   是否允许热更新  并且热更新的是否匹配
            if (isHotfixMethodLevel && hotfixMethodList != null) {
                for (String item : hotfixMethodList) {
                    if (name.matches(item)) {
                        return true;
                    }
                }
            }
//   代表是方法指令
            boolean isMethodInvoke =
                    methodInstructionTypeMap.getOrDefault(name + desc, false);
            //遍历指令类型，
            if (!isMethodInvoke) {
                return false;
            }

            return !isHotfixMethodLevel;

        }

        class MethodBodyInsertor extends GeneratorAdapter implements Opcodes {
            private String className;
            private Type[] argsType;
            private Type returnType;
            List<Type> paramsTypeClass = new ArrayList();
            boolean isStatic;
            //目前methodid是int类型的，未来可能会修改为String类型的，这边进行了一次强转
            String methodId;

            public MethodBodyInsertor(MethodVisitor mv, String className,
                                      String desc, boolean isStatic,
                                      String methodId, String name, int access) {
                super(Opcodes.ASM5, mv, access, name, desc);
                this.className = className;
                this.returnType = Type.getReturnType(desc);
                Type[] argsType = Type.getArgumentTypes(desc);
                for (Type type : argsType) {
                    paramsTypeClass.add(type);
                }
                this.isStatic = isStatic;
                this.methodId = methodId;
            }


            @Override
            public void visitCode() {
                //insert code here
                RobustAsmUtils.createInsertCode(this, className,
                        paramsTypeClass, returnType,
                        isStatic, Integer.valueOf(methodId));
            }

        }

        private boolean isStatic(int access) {
            return (access & Opcodes.ACC_STATIC) != 0;
        }

    }

    /**
     * @param b1 输入的class文件字节流
     * @param className 类的全路径名字 也是当前的流的类名字
     *
     * @return
     *
     * @throws IOException
     */
    public byte[] transformCode(byte[] b1, String className) throws IOException {
//        这里是采用自动计算的方式 计算方法的最大栈 或者 方法的最大的行数
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
//       类 读取器
        ClassReader cr = new ClassReader(b1);
//        类的节点
        ClassNode classNode = new ClassNode();
//        方法的操作类型
        Map<String, Boolean> methodInstructionTypeMap = new HashMap<>();
//        通过字节数据然后读取到CLassNode的节点当中 这里应该说明的接受什么class
        cr.accept(classNode, 0);
//        类的所有方法
        final List<MethodNode> methods = classNode.methods;
        for (MethodNode m : methods) {
//           方法的指令列表
            InsnList inList = m.instructions;
//            查询一下方法的指令里面是否包含 方法指令 如果包含的话 就代表这个方法
            boolean isMethodInvoke = false;
            for (int i = 0; i < inList.size(); i++) {
                if (inList.get(i).getType() == AbstractInsnNode.METHOD_INSN) {
                    isMethodInvoke = true;
                }
            }
            methodInstructionTypeMap.put(m.name + m.desc, isMethodInvoke);
        }
//        插入方法的适配器
        InsertMethodBodyAdapter insertMethodBodyAdapter
                = new InsertMethodBodyAdapter(cw, className, methodInstructionTypeMap);
//
        cr.accept(insertMethodBodyAdapter, ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }

}
