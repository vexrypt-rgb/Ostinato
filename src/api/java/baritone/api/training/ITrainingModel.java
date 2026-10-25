package baritone.api.training;

/** Ostinato: swappable task policy. Callers load it by id and an optional file, not by a hardwired class. */
public interface ITrainingModel {

    String id();

    TaskDecision decide(ITaskView view);
}
