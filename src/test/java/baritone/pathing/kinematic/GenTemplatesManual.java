package baritone.pathing.kinematic;

import org.junit.Test;

public class GenTemplatesManual {
    @Test
    public void gen() throws Exception {
        if (System.getProperty("gen.out") == null && System.getenv("GEN_OUT") == null) return;
        JumpTemplates.main(new String[]{System.getenv("GEN_OUT")});
    }
}
