package tests;

import misc.UVConfig;
import network.UVNetwork;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LegacySnapshotCompatibilityTest {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        Path config = directory.resolve("current.properties");
        Files.writeString(config, "@include=" + directory.resolve("legacy.properties") + "\nlogfile=" + directory.resolve("current.log") + "\n");
        UVNetwork.Log = ignored -> {};
        var network = new UVNetwork(new UVConfig(config.toString()));
        try {
            if (!network.loadStatus(directory.resolve("legacy.dat").toString())) throw new AssertionError(network.getLastLoadStatusError());
            if (!network.isPaused() || network.getUVNodeList().size() != 2 || !network.isImportedObserverView()) throw new AssertionError("Legacy state lost");
            var node = network.getUVNode("b");
            var previous = node.getGeneratedInvoices().values().iterator().next();
            var next = node.generateInvoice(1000, "default", true);
            if (previous.equals(next)) throw new AssertionError("Legacy identity reused");
            network.resume();
            network.generateRootInvoiceEvents(2, 1, 1000, 1000, 1000);
            if (!Boolean.TRUE.equals(network.getLastInvoiceBatch().snapshot().get("settled"))) throw new AssertionError("Root traffic did not settle");
            network.pauseAndDrain();
            network.saveStatus(directory.resolve("upgraded.dat").toString());
            if (!network.loadStatus(directory.resolve("upgraded.dat").toString())) throw new AssertionError("Upgraded snapshot unreadable");
            System.out.println("Original 1430982 snapshot loads, root traffic settles, and upgraded state reloads");
        } finally { network.shutdown(); }
    }
}
