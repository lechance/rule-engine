package org.jetlinks.rule.engine.cluster;

import lombok.SneakyThrows;
import org.jetlinks.core.event.EventBus;
import org.jetlinks.core.rpc.RpcService;
import org.jetlinks.core.rpc.RpcServiceFactory;
import org.jetlinks.rule.engine.api.RuleData;
import org.jetlinks.rule.engine.api.model.RuleLink;
import org.jetlinks.rule.engine.api.model.RuleModel;
import org.jetlinks.rule.engine.api.model.RuleNodeModel;
import org.jetlinks.rule.engine.cluster.scheduler.ClusterLocalScheduler;
import org.jetlinks.rule.engine.defaults.LambdaTaskExecutorProvider;
import org.jetlinks.rule.engine.defaults.LocalWorker;
import org.jetlinks.supports.rpc.DefaultRpcServiceFactory;
import org.jetlinks.supports.rpc.EventBusRpcService;
import org.junit.Assert;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

public abstract class AbstractClusterRuleEngineTest {

    public abstract EventBus getEventBus();

    @Test
    @SneakyThrows
    public void test() {
        EventBus eventBus = getEventBus();
        RpcServiceFactory factory=new DefaultRpcServiceFactory(new EventBusRpcService(eventBus));

        ClusterSchedulerRegistry registry = new ClusterSchedulerRegistry(eventBus, factory);
        registry.setKeepaliveInterval(Duration.ofMillis(2000));
        AtomicLong counter = new AtomicLong();
        AtomicLong event = new AtomicLong();

        //模拟集群节点1
        {
            ClusterLocalScheduler scheduler = new ClusterLocalScheduler("test", factory);
            LocalWorker worker = new LocalWorker("local", "Local", eventBus, (c, d) -> true);

            worker.addExecutor(new LambdaTaskExecutorProvider("createBoom", ruleData -> {
                counter.incrementAndGet();
                return Mono.just(ruleData.newData("boom"));
            }));
            scheduler.addWorker(worker);
            registry.register(scheduler);
            registry.setup();
        }

        //模拟集群节点2
        {
            EventBus eventBus2 = getEventBus();
            RpcService rpcService2 = new EventBusRpcService(eventBus2);
            RpcServiceFactory factory2=new DefaultRpcServiceFactory(rpcService2);

            ClusterSchedulerRegistry registry2 = new ClusterSchedulerRegistry(eventBus2, factory2);
            registry2.setKeepaliveInterval(Duration.ofMillis(2000));
            ClusterLocalScheduler scheduler = new ClusterLocalScheduler("test2", factory2);

            LocalWorker worker = new LocalWorker("local2", "Local2", eventBus2, (c, d) -> true);
            worker.addExecutor(new LambdaTaskExecutorProvider("createWorld", ruleData -> Mono.just(ruleData.newData("world"))));
            worker.addExecutor(new LambdaTaskExecutorProvider("event", ruleData -> {
                event.incrementAndGet();
                return Mono.just(ruleData.newData("event"));
            }));
            scheduler.addWorker(worker);
            registry2.register(scheduler);
            registry2.setup();
        }

        Thread.sleep(1000);

        ClusterRuleEngine engine = new ClusterRuleEngine(registry, new TestTaskSnapshotRepository());

        RuleModel model = new RuleModel();
        model.setId("test");
        model.setName("测试模型");

        {
            RuleNodeModel node1 = new RuleNodeModel();
            node1.setId("createWorld");
            node1.setName("测试节点");
            node1.setExecutor("createWorld");

            RuleNodeModel node2 = new RuleNodeModel();
            node2.setId("createBoom");
            node2.setName("测试节点2");
            node2.setExecutor("createBoom");

            RuleNodeModel eventNode = new RuleNodeModel();
            eventNode.setId("event");
            eventNode.setName("事件处理");
            eventNode.setExecutor("event");


            RuleLink link = new RuleLink();
            link.setSource(node1);
            link.setTarget(node2);
            link.setId("1-2");

            node1.getOutputs().add(link);
            node2.getInputs().add(link);

            RuleLink eventLink = new RuleLink();
            eventLink.setSource(node2);
            eventLink.setTarget(eventNode);
            eventLink.setId("1-3");
            eventLink.setType("complete");

            node2.getEvents().add(eventLink);

            model.getNodes().add(eventNode);
            model.getNodes().add(node1);
            model.getNodes().add(node2);

        }

        engine.startRule("test", model)
                .doOnNext(task -> {
                    System.out.println(task.getSchedulerId());
                    System.out.println(task.getWorkerId());
                })
                .as(StepVerifier::create)
                .expectNextCount(3)
                .verifyComplete();

        engine.getTasks("test")
                .filter(task -> task.getJob().getNodeId().equals("createWorld"))
                .take(1)
                .flatMap(task -> task.execute(RuleData.create("test")))
                .as(StepVerifier::create)
                .expectComplete()
                .verify();

        Thread.sleep(2000);
        Assert.assertEquals(counter.get(), 1);
        Assert.assertEquals(event.get(), 1);

//        engine.shutdown("test")
//                .as(StepVerifier::create)
//                .expectComplete()
//                .verify();
    }
}
