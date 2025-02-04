package eu.qped.java.checkers.coverage;

import eu.qped.framework.*;
import eu.qped.framework.qf.*;
import eu.qped.java.checkers.coverage.feedback.Formatter;
import eu.qped.java.checkers.coverage.feedback.Summary;
import eu.qped.java.checkers.coverage.feedback.wanted.ParserWF;
import eu.qped.java.checkers.coverage.framework.ast.*;
import eu.qped.java.checkers.coverage.framework.coverage.*;
import eu.qped.java.checkers.coverage.framework.test.*;
import eu.qped.java.utils.compiler.Com;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Creates a custom code coverage for given list of classes
 * @author Herfurth
 */
public class CoverageBlockChecker implements Checker, CoverageChecker {

    // frameworks
    // defines what frameworks are used
    private static final String COVERAGE_FRAMEWORK = "JACOCO", AST_FRAMEWORK = "JAVA_PARSER", TEST_FRAMEWORK = "JUNIT5";

    // conventions
    // defines what classnames and testclass are
    private static final String MAVEN = "MAVEN", JAVA = "JAVA";

    private class Info implements CovInformation {
        private final byte[] byteCode;
        private final String classname;
        private final String content;

        public Info(byte[] byteCode, String classname, String content) {
            this.byteCode = byteCode;
            this.classname = classname;
            this.content = content;
        }

        @Override
        public String simpleClassName() {
            return classname.substring(classname.lastIndexOf(".") + 1);
        }

        @Override
        public String content() {
            return content;
        }

        @Override
        public String className() {
            return classname;
        }

        @Override
        public byte[] byteCode() {
            return byteCode;
        }
    }

    ZipService.Extracted extracted;

    QfCovSetting covSetting;
    @QfProperty
    FileInfo file = null;
    @QfProperty
    String privateImplementation = null;
    @QfProperty
    String answer = null;

    public CoverageBlockChecker() {

    }

    public CoverageBlockChecker(QfCovSetting covSetting) {
        this.covSetting = covSetting;
        this.file = covSetting.getFile();
        this.privateImplementation = covSetting.getPrivateImplementation();
        this.answer = covSetting.getAnswer();
    }

    public Summary checker(List<CovInformation> testClasses, List<CovInformation> classes) {
        Summary summary = new Summary();
        try {
            AstFramework ast = AstFrameworkFactoryAbstract.create(AST_FRAMEWORK).create();
            TestFrameworkFactory test = TestFrameworkFactoryAbstract.create(TEST_FRAMEWORK);
            CoverageFramework coverage = CoverageFrameworkFactoryAbstract.create(COVERAGE_FRAMEWORK).create(test);

            summary = (Summary) ast.analyze(
                    summary,
                    new LinkedList<>(classes),
                    covSetting.getExcludeByType(),
                    covSetting.getExcludeByName());

            summary = (Summary) coverage.analyze(
                    summary,
                    new LinkedList<>(testClasses),
                    new LinkedList<>(classes));

            summary.analyze(new ParserWF().parse(covSetting.getLanguage(), covSetting.getFeedback()));
            return summary;
        } catch (Exception e) {
            throw new InternalError(ErrorMSG.UPS, e);
        }
    }

    public String[] check()  {
        CoverageSetup.Data data = covSetting.getData();
        data.cleanUp(); // TODO
        if (! data.isCompiled) {
            return data.syntaxFeedback.toArray(String[]::new);
        }

        try {
            return Formatter.format(
                    covSetting.getFormat(),
                    checker(data.testclasses, data.classes));
        } catch (Exception e) {
            return new String[] {e.getMessage()};
        }
    }

//    public List<String> check()  {
//        return  Arrays.stream(setUp()).collect(Collectors.toList());
//    }

    @Override
    public void check(QfObject qfObject) throws Exception {
        qfObject.setFeedback(setUp());
    }

    private String[] setUp() {
        try {
            Zip zip = new Zip();
            extracted = extract(zip);

            Map<String, File> fileByClassname = extracted.javafileByClassname();
            List<String> testClasses = extracted.testClasses();
            List<String> classes = extracted.classes();

            Com compiler = new Com();

            // Creates a class from a string answer
            if (Objects.nonNull(answer) && !answer.isBlank()) {
                Com.Created f = compiler.createClassFromString(extracted.root(), answer);

                if (f.isTrue) {
                    if (Pattern.matches(".*Test$", f.className)) {
                        testClasses.add(f.className);

                    } else {
                        classes.add(f.className);

                    }
                    fileByClassname.put(f.className, f.file);
                }
            }

            // Compiles all classes
            if (! compiler.compileSource(extracted.root())) {
                System.out.println(compiler.protocol());
                List<String> failed = compiler.protocol().stream().map(s-> s.toString()).collect(Collectors.toList());
                System.out.println(compiler.protocol());
                failed.add(0, "Ups there are some compile issues: ");
                return failed.toArray(String[]::new);
            }

            // Validates if at least on testclass and on class are present
            if (classes.isEmpty())
                throw new IllegalStateException(ErrorMSG.MISSING_CLASS);

            if (testClasses.isEmpty())
                throw new IllegalStateException(ErrorMSG.MISSING_TESTCLASS);

            Summary summary = checker(
                    preprocessing(fileByClassname, testClasses),
                    preprocessing(fileByClassname, classes));
            zip.cleanUp();

            return Formatter.format(covSetting.getFormat(), summary);
        } catch (Exception e) {
            return new String[]{e.getMessage()};
        }
    }



    private ZipService.Extracted extract(ZipService zipService) {
        try {
            ZipService.Classname classname;
            ZipService.TestClass testClass;

            if (covSetting.getConvention().equals(JAVA)) {
                classname = ZipService.JAVA_CLASS_NAME;
                testClass = ZipService.JAVA_TEST_CLASS;

            } else if (covSetting.getConvention().equals(MAVEN)) {
                classname = ZipService.MAVEN_CLASS_NAME;
                testClass = ZipService.MAVEN_TEST_CLASS;

            } else {
                throw new IllegalStateException(ErrorMSG.UPS);

            }

            if (Objects.nonNull(file) && (Objects.nonNull(privateImplementation) && !privateImplementation.isBlank())) {
                return zipService.extractBoth(
                        file.getSubmittedFile(),
                        zipService.download(privateImplementation),
                        testClass,
                        classname);

            } else if (Objects.nonNull(file)) {
                return zipService.extract(file.getSubmittedFile(),testClass, classname);

            } else if (Objects.nonNull(privateImplementation) && !privateImplementation.isBlank()) {
                return zipService.extract(zipService.download(privateImplementation),testClass, classname);
            }
            throw new Exception(ErrorMSG.MISSING_FILES);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }


    private LinkedList<CovInformation> preprocessing(
            Map<String, File> javafileByClassname,
            List<String> classname)  {
        LinkedList<CovInformation> infos = new LinkedList<>();
        for (String name : classname) {
            if (covSetting.getConvention().equals(MAVEN)) {
                infos.add(new Info(
                        readByteCode(Path.of(extracted.root().getAbsolutePath() +"/" + name.replace(".","/") + ".class").toString()),
                        name,
                        readJavacontent(javafileByClassname.get(name))));

            } else {
                infos.add(new Info(
                        readByteCode(javafileByClassname.get(name).getAbsolutePath().replaceAll("\\.java$", ".class")),
                        name,
                        readJavacontent(javafileByClassname.get(name))));

            }
        }
        return infos;
    }

    private String readJavacontent(File file) {
        try {
            return Files.readAllLines(file.toPath()).stream().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            throw new InternalError(String.format(ErrorMSG.CANT_READ_FILE, file.toString()));
        }
    }

    private byte[] readByteCode(String file) {
        try {
            return Files.readAllBytes(Paths.get(file));
        } catch (Exception e) {
            throw new InternalError(String.format(ErrorMSG.CANT_READ_FILE, file.toString()));
        }
    }
}
