package stats;

import misc.UVConfig;
import network.*;
import protocol.UVTimechain;
import topology.PathFinderFactory;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Future;

/** Restricted zero-invoice branch audit. Does not claim support for arbitrary traffic snapshots. */
public final class InitialS50State {
    private InitialS50State() {}
    private static Object field(Object o, String name) {
        for (Class<?> c=o.getClass();c!=null;c=c.getSuperclass()) try {
            var f=c.getDeclaredField(name);f.setAccessible(true);return f.get(o);
        } catch(NoSuchFieldException ignored) {} catch(ReflectiveOperationException e) {throw new IllegalStateException(e);}
        throw new IllegalArgumentException("Unknown branch field " + name);
    }
    private static void check(boolean condition,String message) {if(!condition)throw new IllegalStateException(message);}

    private static String value(Object o) {
        if(o==null)return "null";
        if(o instanceof String s)return "String:"+JournalCaptureExporter.canonical(s);
        if(o instanceof Number || o instanceof Boolean || o instanceof Enum<?>)return o.getClass().getName()+":"+o;
        if(o instanceof Random) {
            try {var b=new ByteArrayOutputStream();try(var s=new ObjectOutputStream(b)){s.writeObject(o);}return "Random:"+JournalCaptureExporter.sha256(b.toByteArray());}
            catch(IOException e){throw new UncheckedIOException(e);}
        }
        if(o instanceof Map<?,?> m){var a=new ArrayList<String>();for(var e:m.entrySet())a.add(value(e.getKey())+"="+value(e.getValue()));Collections.sort(a);return "Map"+a;}
        if(o instanceof Collection<?> a){var b=new ArrayList<String>();for(var v:a)b.add(value(v));if(o instanceof Set<?>)Collections.sort(b);return (o instanceof Set<?>?"Set":o instanceof Queue<?>?"Queue":"List")+b;}
        if(o.getClass().isArray()){var a=new ArrayList<String>();for(int i=0;i<Array.getLength(o);i++)a.add(value(Array.get(o,i)));return "Array"+a;}
        if(!Set.of("network","protocol","topology","misc","stats").contains(o.getClass().getPackageName()))throw new IllegalStateException("Unsupported branch value "+o.getClass());
        var a=new TreeMap<String,String>();
        for(Class<?> c=o.getClass();c!=null&&c!=Object.class;c=c.getSuperclass())for(var f:c.getDeclaredFields()){
            if(Modifier.isStatic(f.getModifiers())||f.isSynthetic())continue;
            if(o instanceof UVNode && Set.of("uvNetwork","p2pHandler","saved_peers_id","peers").contains(f.getName()))continue;
            if(o instanceof UVTimechain && Set.of("uvm","timechainThread","wait_blocks_latch").contains(f.getName()))continue;
            // Runtime wiring to the owning network (edge sharing), not node state: following it would walk the whole network.
            if(o instanceof topology.ChannelGraph && f.getName().equals("edgeInterner"))continue;
            try{f.setAccessible(true);a.put(c.getName()+"."+f.getName(),value(f.get(o)));}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
        }
        if(o instanceof UVNode n) {
            Collection<?> ids=n.getPeers()==null?(Collection<?>)field(n,"saved_peers_id"):n.getPeers().keySet();
            a.put("bound_peer_ids",value(new TreeSet<>(ids)));
        }
        return o.getClass().getName()+a;
    }
    public static String digest(Object o){return JournalCaptureExporter.sha256(value(o).getBytes(StandardCharsets.UTF_8));}
    public static Map<String,Object> describe(UVConfig config,UVTimechain clock,Map<String,UVNode> nodes,Random random,
            String topology,String root,Map<String,Object> receipt){
        var result=new TreeMap<String,Object>();
        result.put("version",UVNetwork.INITIAL_BRANCH_VERSION);result.put("effective_config",digest(config.getEffectiveSettings()));
        result.put("profiles",digest(config.getNodeProfiles()));result.put("timechain",digest(clock));result.put("native_random",digest(random));
        result.put("topology",topology);result.put("imported_root",root);result.put("allocation_receipt",digest(receipt));
        var components=new TreeMap<String,Object>();
        for(var node:nodes.values()){
            var order=new TreeMap<String,Object>();
            for(var e:node.getChannelGraph().getAdjMap().entrySet()){
                var rows=new ArrayList<Object>();for(var edge:e.getValue())rows.add(List.of(edge.id(),edge.source(),edge.destination(),edge.capacity(),digest(edge.policy())));order.put(e.getKey(),rows);
            }
            components.put(node.getPubKey(),Map.of("all_node_state",digest(node),"routing_iteration_order",digest(order)));
        }
        result.put("nodes",components);return result;
    }
    public static String fingerprint(UVConfig config,UVTimechain clock,Map<String,UVNode> nodes,Random random,
            String topology,String root,Map<String,Object> receipt){return digest(describe(config,clock,nodes,random,topology,root,receipt));}

    public static void validate(UVConfig config,UVTimechain clock,Map<String,UVNode> nodes,Random random,Map<String,Object> receipt,boolean reading){
        check(receipt!=null&&"raw-half-lexicographic-v1".equals(receipt.get("allocation_version")),"Missing exact S50 receipt");
        check(((Number)receipt.get("invoice_requests")).longValue()==0&&((Number)receipt.get("invoice_successes")).longValue()==0,"Non-initial receipt");
        long seed=((Number)receipt.get("traffic_seed")).longValue();check(seed>0&&digest(random).equals(digest(new Random(seed))),"Initial native traffic RNG mismatch");
        check(!clock.getStatus()&&!clock.isThreadAlive(),"Initial branch clock must be joined");
        check(((Collection<?>)field(clock,"mempoolQueue")).isEmpty(),"Initial branch mempool must be empty");
        var waits=(Collection<?>)field(clock,"wait_blocks_latch");check(waits==null||waits.isEmpty(),"Initial branch has clock waiters");
        var channels=new TreeMap<String,UVChannel>();
        for(var node:nodes.values()){
            check(node.getClass()==UVNode.class,"Unsupported node subclass");
            check(!((Boolean)field(node,"p2pIsRunning")),"Initial node worker is running");
            Object handler=field(node,"p2pHandler");check(handler==null||handler instanceof Future<?> f&&f.isDone(),"Initial node worker not joined");
            check(node.unsettledState().isEmpty(),"Initial node has unsettled protocol state");
            check(((Map<?,?>)field(node,"failure_reason")).isEmpty(),"Unsaved initial failure cache");
            check(node.getGeneratedInvoices().isEmpty()&&node.getPayedInvoices().isEmpty()&&node.getInvoiceReports().isEmpty(),"Initial node has invoice history");
            var strategy=(PathFinderFactory.Strategy)field(node,"savedPathFinderStrategy");
            var expected=PathFinderFactory.of(strategy,config);
            if(reading)node.setPathFinder(expected);
            check(node.getPathFinder()!=null&&digest(node.getPathFinder()).equals(digest(expected)),"Unsupported mutable pathfinder state");
            Collection<?> peers=node.getPeers()==null?(Collection<?>)field(node,"saved_peers_id"):node.getPeers().keySet();
            check(peers!=null&&nodes.keySet().containsAll(peers),"Invalid initial peer identities");
            if(node.getPeers()!=null)for(var e:node.getPeers().entrySet())check(nodes.get(e.getKey())==e.getValue(),"Foreign initial peer object");
            for(var edges:node.getChannelGraph().getAdjMap().values())if(reading)check(edges instanceof LinkedHashSet<?>,"Snapshot does not preserve routing iteration order");
            for(var channel:node.getChannels().values()){
                check(channel.getClass()==UVChannel.class,"Unsupported channel subclass");
                var old=channels.putIfAbsent(channel.getChannelId(),channel);check(old==null||old==channel,"Duplicate physical channel");
                SymmetricAllocation.preflight(channel);
                String a=channel.getNode1PubKey(),b=channel.getNode2PubKey();
                check(nodes.containsKey(a)&&nodes.containsKey(b)&&nodes.get(a).getChannels().get(channel.getChannelId())==channel&&nodes.get(b).getChannels().get(channel.getChannelId())==channel,"Invalid shared endpoint channel");
                check(channel.getBalance(a)==channel.getCapacity()/2&&channel.getBalance(b)==channel.getCapacity()-channel.getCapacity()/2,"Branch is not exact initial S50");
            }
        }
        check(!nodes.isEmpty()&&!channels.isEmpty()&&((Number)receipt.get("channels")).intValue()==channels.size(),"Initial receipt channel count mismatch");
    }
}
