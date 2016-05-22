package meetup.akka.actor;

import akka.actor.ActorPath;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Function;
import akka.persistence.UntypedPersistentActorWithAtLeastOnceDelivery;
import akka.routing.RoundRobinPool;
import com.google.common.base.MoreObjects;
import meetup.akka.dal.OrderDao;
import meetup.akka.om.CompleteBatch;
import meetup.akka.om.NewOrder;

public class OrderProcessor extends UntypedPersistentActorWithAtLeastOnceDelivery {
  private static final int PERSISTENCE_ACTORS = 5;
  private final ActorRef orderIdGenerator;
  private final ActorPath orderLogger;
  private final LoggingAdapter log = Logging.getLogger(getContext().system(), this);

  public OrderProcessor(OrderDao orderDao) {
    orderIdGenerator = getContext().actorOf(Props.create(OrderIdGenerator.class), "orderIdGenerator");
    orderLogger = getContext()
            .actorOf(new RoundRobinPool(PERSISTENCE_ACTORS).props(Props.create(OrderLogger.class, orderDao)), "orderLogger")
            .path();
  }

  @Override
  public void onReceiveRecover(Object msg) throws Exception {
    generateOrderId(msg);
  }

  @Override
  public void onReceiveCommand(Object msg) throws Exception {
    if (msg instanceof NewOrder) {
      log.info("New order received. Going to generate an id: {}", msg);
      persist(msg, this::generateOrderId);

    } else if (msg instanceof PreparedOrder) {
      PreparedOrder preparedOrder = (PreparedOrder) msg;
      log.info("Prepared order received with id = {}, {}", preparedOrder.orderId, preparedOrder.order);
      persist(preparedOrder, this::updateState);

    } else if (msg instanceof LoggedOrder) {
      log.info("Logging confirmation received for order: {}", msg);
      updateState(msg);
      log.info("Delivery confirmed for order = {}", msg);

    } else if (msg instanceof CompleteBatch) {
      log.info("Going to complete batch.");
      getContext().actorSelection(orderLogger).tell(msg, self());

    } else {
      unhandled(msg);
    }
  }

  private void generateOrderId(Object event) {
    if (event instanceof NewOrder) {
      orderIdGenerator.tell(((NewOrder) event).order, self());
    }
  }

  private void updateState(Object event) {
    if (event instanceof PreparedOrder) {
      deliver(orderLogger, (Function<Long, Object>) deliveryId -> new PreparedOrderForAck(deliveryId, (PreparedOrder) event));
    } else if (event instanceof LoggedOrder) {
      confirmDelivery(((LoggedOrder) event).deliveryId);
    }
  }

  @Override
  public String persistenceId() {
    return "orders";
  }
}

class PreparedOrderForAck {
  public final long deliveryId;
  public final PreparedOrder preparedOrder;

  public PreparedOrderForAck(long deliveryId, PreparedOrder preparedOrder) {
    this.deliveryId = deliveryId;
    this.preparedOrder = preparedOrder;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
            .add("deliveryId", deliveryId)
            .add("preparedOrder", preparedOrder)
            .toString();
  }
}
