import misc.UVConfig;
import misc.json.*;
import network.UVNetwork;
import stats.*;
import topology.PathFinderFactory;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static stats.JournalCaptureExporter.object;
import static stats.JournalCaptureExporter.sha256;

/** Fixed paired initial-state branches; capture only. No model or calibration entry point. */
public final class JournalMixedRunner {
    static JsonObject read(Path p)throws Exception{try(var r=Files.newBufferedReader(p)){return Json.parseObject(r);}}
    static String hash(Path p)throws Exception{return sha256(Files.readAllBytes(p));}
    static String create(Path p,Object value)throws Exception{byte[] bytes=(JournalCaptureExporter.canonical(value)+"\n").getBytes(StandardCharsets.UTF_8);Files.write(p,bytes,StandardOpenOption.CREATE_NEW);return sha256(bytes);}
    static void check(boolean x,String m){if(!x)throw new IllegalStateException(m);}
    static int number(JsonObject row,String key){return ((Number)row.get(key)).intValue();}
    static JsonObject capture(UVNetwork n,Path out,String topology,String cp,long cumulative,JsonObject provenance,String expectedStructure,JsonObject batch)throws Exception{
        Path d=out.resolve("checkpoints").resolve(cp);Files.createDirectory(d);
        var first=JournalS50Exporter.build(n,topology,cp,provenance);var again=JournalS50Exporter.build(n,topology,cp,provenance);check(first.equals(again),"Paused capture changed");
        String structure=(String)first.truth().get("structural_fingerprint");check(expectedStructure==null||structure.equals(expectedStructure),"Public/private structure changed");
        var files=object("public.json",create(d.resolve("public.json"),first.publicGraph()),"truth.json",create(d.resolve("truth.json"),first.truth()),"private.json",create(d.resolve("private.json"),first.privateState()));
        if(batch!=null)files.put("invoices.json",create(d.resolve("invoices.json"),batch));
        var receipt=object("schema","paired-mixed-checkpoint-v2","topology_id",topology,"checkpoint",cp,"cumulative_requests",cumulative,"files_sha256",files,"public_fingerprint",first.truth().get("public_fingerprint"),"structural_fingerprint",structure,"settled",true,"repeated_capture_identical",true,"block_height",n.getTimechain().getCurrentBlockHeight());create(d.resolve("capture.json"),receipt);return receipt;
    }
    public static void main(String[] args){
        int code=1;UVNetwork n=null;Path out=null;
        try{
            check(args.length==1&&Set.of("create","migrate","control","mixture").contains(args[0]),"Mode required");String mode=args[0];Path runtime=Path.of("").toRealPath();check(runtime.getFileName().toString().equals("runtime"),"Runtime directory required");out=runtime.getParent();
            var request=read(out.resolve("request.json"));var source=read(out.resolve("source-manifest.json"));check("paired-mixed-request-v2".equals(request.get("schema")),"Wrong request schema");check("development-capture-only".equals(request.get("role")),"Wrong capture role");check(mode.equals(request.get("mode")),"Mode mismatch");
            String topology=(String)request.get("topology_id"),sourceHash=(String)source.get("source_tree_sha256");long drain=((Number)request.get("drain_timeout_ms")).longValue();
            var config=new UVConfig("capture.properties");check(config.bootstrap_nodes==500&&config.bootstrap_blocks==100&&config.max_threads==128&&config.logfile.equals("simulator.log"),"Fixed capture config differs");
            var configHash=create(out.resolve("expanded-config.json"),config.getEffectiveSettings());UVNetwork.Log=x->{};n=new UVNetwork(config);Files.createDirectory(out.resolve("checkpoints"));
            var provenance=object("capture_id",UUID.randomUUID().toString(),"parent_id",topology,"branch",mode,"bootstrap_seed",config.getMasterSeed(),"source_tree_sha256",sourceHash,"source_manifest_sha256",hash(out.resolve("source-manifest.json")),"request_sha256",hash(out.resolve("request.json")),"expanded_config_sha256",configHash,"created_at_utc",Instant.now().toString(),"capture_boundary","joined-settled-paired-mixed-v2","pathfinder","lnd");
            long start=System.nanoTime();
            if(mode.equals("create") || mode.equals("migrate")){
                String structure;
                Map<String, Object> receipt;
                if(mode.equals("migrate")) {
                    Path previous=Path.of((String)request.get("migration_from_path"));var prior=read(previous.resolve("complete.json"));
                    String priorSource=(String)request.get("migration_source_sha256");
                    check(hash(previous.resolve("initial-s50.dat")).equals(request.get("migration_snapshot_sha256")),"Migration snapshot changed");
                    check(priorSource.equals(((JsonObject)prior.get("provenance")).get("source_tree_sha256")),"Migration source differs");
                    check(n.loadInitialS50Branch(previous.resolve("initial-s50.dat").toString(),priorSource),"Migration source rejected");
                    check(n.initialS50Fingerprint().equals(prior.get("initial_state_fingerprint")),"Migration changed operative state");
                    Path opening=out.resolve("checkpoints/o");Files.createDirectory(opening);
                    for(String file:List.of("public.json","truth.json","private.json","capture.json"))Files.copy(previous.resolve("checkpoints/o").resolve(file),opening.resolve(file));
                    structure=(String)prior.get("structural_fingerprint");receipt=n.getExactS50Receipt();
                    provenance.put("migrated_initial_source",priorSource);provenance.put("migrated_snapshot_sha256",prior.get("snapshot_sha256"));provenance.put("original_common_path",previous.toString());
                    create(out.resolve("migration.json"),object("old_source_sha256",priorSource,"new_source_sha256",sourceHash,"old_snapshot_sha256",prior.get("snapshot_sha256"),"unchanged_initial_state_fingerprint",n.initialS50Fingerprint(),"allocation_repeated",false,"traffic_requests",0));
                } else {
                    n.bootstrapNetwork();n.pauseAndDrain(drain);JournalCaptureExporter.validateRecords(new ArrayList<>(n.getUVNodeList().values()));
                    for(var node:n.getUVNodeList().values())node.setPathFinder(PathFinderFactory.of(PathFinderFactory.Strategy.LND,config));
                    var opening=capture(n,out,topology,"o",0,provenance,null,null);structure=(String)opening.get("structural_fingerprint");
                    receipt=n.initializeExactS50(((Number)request.get("native_traffic_seed")).longValue());
                }
                create(out.resolve("initialization.json"),receipt);var initial=capture(n,out,topology,"s50",0,provenance,structure,null);
                var description=n.initialS50Description();String fingerprint=n.initialS50Fingerprint();create(out.resolve("initial-state-description.json"),description);
                n.saveInitialS50Branch(out.resolve("initial-s50.dat").toString(),sourceHash);check(fingerprint.equals(n.initialS50Fingerprint()),"Save changed initial state");
                create(out.resolve("complete.json"),object("schema","paired-mixed-common-v2","topology_id",topology,"provenance",provenance,"initial_state_fingerprint",fingerprint,"native_random_fingerprint",n.pausedNativeRandomFingerprint(),"snapshot_sha256",hash(out.resolve("initial-s50.dat")),"snapshot_bytes",Files.size(out.resolve("initial-s50.dat")),"description_sha256",hash(out.resolve("initial-state-description.json")),"initialization_sha256",hash(out.resolve("initialization.json")),"public_fingerprint",initial.get("public_fingerprint"),"structural_fingerprint",structure,"native_requests",0,"settled",true,"native_seconds",(System.nanoTime()-start)/1e9));
            }else{
                Path common=Path.of((String)request.get("common_path"));Path schedulePath=Path.of((String)request.get("schedule_path"));var parent=read(common.resolve("complete.json"));var schedule=read(schedulePath);check(topology.equals(parent.get("topology_id"))&&topology.equals(schedule.get("topology_id")),"Parent identity mismatch");
                check(hash(common.resolve("initial-s50.dat")).equals(parent.get("snapshot_sha256")),"Snapshot changed");check(hash(schedulePath).equals(request.get("schedule_sha256")),"Schedule changed");check(sourceHash.equals(((JsonObject)parent.get("provenance")).get("source_tree_sha256")),"Parent source mismatch");
                check(n.loadInitialS50Branch(common.resolve("initial-s50.dat").toString(),sourceHash),"Strict branch rejected: "+n.getLastLoadStatusError());String initial=n.initialS50Fingerprint();check(initial.equals(parent.get("initial_state_fingerprint")),"Branch operative state mismatch");check(n.getConfig().getEffectiveSettings().equals(config.getEffectiveSettings()),"Restored config differs");
                provenance.put("common_snapshot_sha256",parent.get("snapshot_sha256"));provenance.put("common_state_fingerprint",initial);provenance.put("schedule_sha256",hash(schedulePath));provenance.put("sampling_mode","four-named-streams-saved-schedule-v1");
                create(out.resolve("initial-state-description.json"),n.initialS50Description());create(out.resolve("branch-initial-state.json"),object("common_state_fingerprint",initial,"snapshot_sha256",parent.get("snapshot_sha256"),"native_random_fingerprint",n.pausedNativeRandomFingerprint(),"allocation_receipt",n.getExactS50Receipt(),"source_tree_sha256",sourceHash,"independent_process_id",ProcessHandle.current().pid()));
                String structure=(String)parent.get("structural_fingerprint");capture(n,out,topology,"s50",0,provenance,structure,null);
                var rows=(JsonArray)schedule.get("rows");check(rows.size()==10000,"Wrong schedule size");int cumulative=0;var summaries=new JsonArray();
                for(int interval=0;interval<2;interval++){
                    String name=interval==0?"e5":"e20";int count=interval==0?2500:7500,blocks=interval==0?250:750;var planned=new ArrayList<UVNetwork.ScheduledInvoice>();
                    for(int i=cumulative;i<cumulative+count;i++){var row=(JsonObject)rows.get(i);check(name.equals(row.get("interval")),"Wrong interval");planned.add(new UVNetwork.ScheduledInvoice(((Number)row.get("position")).longValue(),number(row,"interval_block"),(String)row.get("sender"),(String)row.get("destination"),number(row,mode.equals("control")?"small_amount_sat":"mixture_amount_sat")));}
                    var batch=n.generateScheduledInvoiceBatch(name,.02,blocks,planned,1000,drain).snapshot();var actual=(JsonArray)batch.get("requests");check(actual.size()==count,"Replay count differs");int origin=number((JsonObject)actual.getFirst(),"submitted_height");
                    for(int j=0;j<count;j++){var a=(JsonObject)actual.get(j);var q=planned.get(j);check(number(a,"submitted_height")==origin+q.relativeBlock(),"Realized timing differs");check(((Number)a.get("request_id")).longValue()==q.position()&&q.sender().equals(a.get("sender"))&&q.destination().equals(a.get("destination"))&&q.amountSat()==number(a,"amount_sat"),"Realized request differs");a.put("planned_interval_block",q.relativeBlock());a.put("planned_traffic_block",number((JsonObject)rows.get(cumulative+j),"relative_block"));a.put("mixture_band",((JsonObject)rows.get(cumulative+j)).get("band"));}
                    batch.put("replay_pacing",n.getLastScheduledTiming());batch.put("schema","paired-mixed-invoices-v3");batch.put("sampling_mode","saved-scheduled-replay");batch.put("replay_origin_height",origin);batch.put("schedule_sha256",hash(schedulePath));cumulative+=count;capture(n,out,topology,name,cumulative,provenance,structure,batch);summaries.add(batch.get("summary"));
                }
                check(n.pausedNativeRandomFingerprint().equals(parent.get("native_random_fingerprint")),"Replay consumed native RNG");
                create(out.resolve("complete.json"),object("schema","paired-mixed-trajectory-v2","topology_id",topology,"branch",mode,"provenance",provenance,"initial_state_fingerprint",initial,"native_random_fingerprint_after",n.pausedNativeRandomFingerprint(),"total_requests",cumulative,"summaries",summaries,"settled",true,"native_seconds",(System.nanoTime()-start)/1e9));
            }
            code=0;System.out.println("Completed "+mode+" "+topology);
        }catch(Throwable e){e.printStackTrace(System.err);if(out!=null)try{create(out.resolve("failure.json"),object("error",e.toString(),"unsettled",n==null?null:n.unsettledState(),"last_batch",n==null||n.getLastInvoiceBatch()==null?null:n.getLastInvoiceBatch().snapshot()));}catch(Throwable nested){nested.printStackTrace(System.err);}}
        finally{if(n!=null)n.shutdown();}System.exit(code);
    }
}
