// H2 resolution / link probe — csvsql Batch 1.
// Purpose: prove that H2 resolves at RUNTIME and that CSVREAD works, exactly as the csvsql
// executor uses it. The executor itself is pure java.sql (no org.h2.* imports), so the project
// compiles without H2; this probe only needs H2 on the classpath at run time.
//
// Usage (after making the H2 jar available, see README_H2.md):
//   javac H2Probe.java
//   java -cp .;h2-2.1.214.jar H2Probe      (Windows: ';' separator)
//   java -cp .:h2-2.1.214.jar H2Probe      (Linux:   ':' separator)
//
// Expected output: "H2 OK: CSVREAD rows = 2".
import java.io.File;
import java.io.FileWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

public class H2Probe {
    public static void main(String[] args) throws Exception {
        Class.forName("org.h2.Driver");
        File csv = File.createTempFile("h2probe", ".csv");
        FileWriter w = new FileWriter(csv);
        w.write("NDG;NOME\r\n64157069;Mario\r\n71714992;Anna\r\n");
        w.close();

        Connection c = DriverManager.getConnection("jdbc:h2:mem:probe", "sa", "");
        PreparedStatement ps = c.prepareStatement(
                "CREATE TABLE t AS SELECT * FROM CSVREAD(?, NULL, ?)");
        ps.setString(1, csv.getAbsolutePath());
        ps.setString(2, "fieldSeparator=; charset=UTF-8");
        ps.executeUpdate();
        ps.close();

        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t");
        rs.next();
        int rows = rs.getInt(1);
        st.close();
        c.close();
        csv.delete();

        System.out.println("H2 OK: CSVREAD rows = " + rows);
        if (rows != 2) { System.out.println("UNEXPECTED row count"); System.exit(1); }
    }
}
