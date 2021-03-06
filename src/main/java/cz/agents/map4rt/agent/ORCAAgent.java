package cz.agents.map4rt.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.log4j.Logger;
import org.jgrapht.DirectedGraph;

import rvolib.KdTree;
import rvolib.RVOAgent;
import rvolib.RVOObstacle;
import rvolib.RVOUtil;
import rvolib.Vector2;
import tt.euclid2d.Vector;
import tt.euclid2i.EvaluatedTrajectory;
import tt.euclid2i.Line;
import tt.euclid2i.Point;
import tt.euclid2i.Region;
import tt.euclid2i.probleminstance.Environment;
import tt.euclid2i.region.Polygon;
import tt.euclid2i.trajectory.TimePointArrayTrajectory;
import tt.euclid2i.util.Util;
import tt.jointeuclid2ni.probleminstance.RelocationTask;
import util.DesiredControl;
import util.GraphBasedOptimalPolicyController;
import cz.agents.alite.communication.Message;
import cz.agents.map4rt.CommonTime;
import cz.agents.map4rt.agent.PositionBlackboard.Record;
import cz.agents.map4rt.msg.InformNewPosition;

public class ORCAAgent extends Agent {
	
	static final Logger LOGGER = Logger.getLogger(ORCAAgent.class);

	private static final int MAX_NEIGHBORS = 50;
	private static final float NEIGHBOR_DIST = 500;
	private static final float TIME_HORIZON_AGENT = 5000;
	private static final float TIME_HORIZON_OBSTACLE = 1000;

	private static final double NEAR_GOAL_EPS = 0.0f;

	private RVOAgent rvoAgent;
    private HashMap<String, RVOAgent> neighbors;

	private KdTree kdTree;
	private ArrayList<RVOObstacle> obstacles;

	float maxSpeed;
	DesiredControl desiredControl;

	private static final long UNKNOWN = -1;

	private static final double GOAL_REACHED_TOLERANCE = 3;
	private long lastTickTime = UNKNOWN;

	private boolean showVis;

	private Collection<Region> ttObstaclesLessInflated;
	private double DesiredControlNodeSearchRadius;

	private Point currentGoal;

    public ORCAAgent(String name, Point start, int nTasks, Environment environment, DirectedGraph<Point, Line> planningGraph, int agentBodyRadius, float maxSpeed, int maxTime, int timeStep, boolean showVis, Random random) {
        super(name, start, nTasks, environment, planningGraph, agentBodyRadius, maxSpeed, random);

        this.showVis = showVis;
        this.maxSpeed = maxSpeed;

        rvoAgent = new RVOAgent();

        rvoAgent.position_ = new Vector2(start);
        rvoAgent.velocity_ = new Vector2(0,0);

        rvoAgent.maxNeighbors_ = MAX_NEIGHBORS;
        rvoAgent.maxSpeed_ = maxSpeed;
        rvoAgent.neighborDist_ = NEIGHBOR_DIST;
        rvoAgent.radius_ = (float) Math.ceil(agentBodyRadius + 1);
        rvoAgent.timeHorizon_ = TIME_HORIZON_AGENT;
        rvoAgent.timeHorizonObst_ = TIME_HORIZON_OBSTACLE;

        rvoAgent.clearTrajectory();

        rvoAgent.id_ = Integer.parseInt(name.substring(1));

        rvoAgent.showVis = showVis;
        if (showVis) {
        	rvoAgent.initVisualization();
        }

        
        LinkedList<tt.euclid2i.Region> ttObstacles = new LinkedList<tt.euclid2i.Region>();
        ttObstacles.add(environment.getBoundary());
        ttObstacles.addAll(environment.getObstacles());
        
    	this.ttObstaclesLessInflated = Util.inflateRegions(ttObstacles, agentBodyRadius-1);
    	
    	DesiredControlNodeSearchRadius = longestEdgeLength(planningGraph)+1; // Used to be: ((float) Math.ceil(agentBodyRadius * RADIUS_GRACE_MULTIPLIER) * 3) + 1; // approx. sqrt(2) * 2 * radius
    		
		desiredControl = new DesiredControl() {
			@Override
			public Vector getDesiredControl(tt.euclid2d.Point currentPosition) {
				return new Vector(0,0);
			}
		};
		currentGoal = start;
        
        kdTree = new KdTree();

        // Add obstacles
        obstacles = new ArrayList<RVOObstacle>();

 		for (tt.euclid2i.Region region : ttObstacles) {
 			if (!(region instanceof Polygon)) {
 				throw new RuntimeException("Only polygons are supported");
 			}
 			ArrayList<Vector2> obstacle = RVOUtil.regionToVectorList((Polygon) region);
 			RVOUtil.addObstacle(obstacle, obstacles);
 		}

 		kdTree.buildObstacleTree(obstacles.toArray(new RVOObstacle[0]), this.obstacles);

 		neighbors = new HashMap<String, RVOAgent>();
 		neighbors.put(getName(), rvoAgent);
    }

    private double longestEdgeLength(DirectedGraph<Point, Line> planningGraph) {
    	double longestEdgeLength = 0;
    	for (Line edge : planningGraph.edgeSet()) {
			if (longestEdgeLength < edge.getDistance()) {
				longestEdgeLength = edge.getDistance();
			}
		}
    	
    	return longestEdgeLength;
	}

	public EvaluatedTrajectory getEvaluatedTrajectory(Point goal) {
		ArrayList<tt.euclidtime3i.Point> rvoTraj = new ArrayList<tt.euclidtime3i.Point>(rvoAgent.timePointTrajectory);
    	tt.euclidtime3i.Point[] timePointArray = new tt.euclidtime3i.Point[rvoTraj.size()];
    	for (int i=0; i<rvoTraj.size(); i++) {
    		timePointArray[i] = new tt.euclidtime3i.Point(
    				rvoTraj.get(i).getPosition(),
    				rvoTraj.get(i).getTime());
    	}
    	
    	double cost = RVOAgent.evaluateCost(timePointArray, goal);
		TimePointArrayTrajectory traj = new TimePointArrayTrajectory(timePointArray, cost);
    	return traj;
    }
    
    @Override
    public void start() {

    }


    @Override
	public void tick(int time) {
    	
    	if (currentTask != null && currentTaskDestinationReached()) {
    		long prolongT = (CommonTime.currentTimeMs() - lastTaskIssuedAt) - currentTaskBaseDuration;
    		prolongTSum += prolongT;
    		prolongTSumSq += prolongT * prolongT;
    	}
    	
		super.tick(time);
			

        if (showVis) {
//	        try {
//	            Thread.sleep(10);
//	        } catch (InterruptedException e) {}
        }
        
		if (lastTickTime == UNKNOWN) {
			lastTickTime = time;
			return;
		}

		float timeStep = (float) (time - lastTickTime);
		lastTickTime = time;

		doStep(timeStep);
	}

    private void doStep(float timeStep) {
    	
    	LOGGER.trace(getName() + " -- doStep");
    	
    	
    	updateNeighborsFromBlackboard();
    	
		setPreferredVelocity(timeStep);

		RVOAgent[] rvoAgents = neighbors.values().toArray(new RVOAgent[neighbors.values().size()]);

		kdTree.clearAgents();
        kdTree.buildAgentTree(rvoAgents);

        rvoAgent.computeNeighbors(kdTree);
        Vector2 newVelocity = rvoAgent.computeNewVelocity(timeStep);
        rvoAgent.update(timeStep, newVelocity);
        
        PositionBlackboard.recordNewPosition(getName(), rvoAgent.id_, rvoAgent.position_.toPoint2d(), rvoAgent.velocity_.toVector2d(), rvoAgent.radius_);
    }

	private void setPreferredVelocity(float timeStep) {
		Vector2 currentPosition = rvoAgent.position_;
		double distanceToGoal = currentPosition.toPoint2i().distance(getCurrentGoal());

		//if (currentPosition.toPoint2i().distance(getCurrentGoal()) < NEAR_GOAL_EPS) {
			//rvoAgent.setPrefVelocity(new Vector2(0, 0));
		//} else {
			Vector desiredVelocity = desiredControl.getDesiredControl(rvoAgent.position_.toPoint2d());
			assert !Double.isNaN(desiredVelocity.x) && !Double.isNaN(desiredVelocity.y);
			double desiredSpeed = desiredVelocity.length();
			Vector desiredDir = new Vector(desiredVelocity);
			if (desiredDir.length() != 0) {
				desiredDir.normalize();
			}

			// Adjust if the agent is near the goal
			if (distanceToGoal <= timeStep * desiredSpeed) {
				// goal will be reached in the next time step
				double speed = distanceToGoal / timeStep;
				desiredVelocity = desiredDir;
				desiredVelocity.scale(speed);
			}
			
			rvoAgent.setPrefVelocity(new Vector2(desiredVelocity));
		//}
	}

    protected void updateNeighborsFromBlackboard() {
        for (Map.Entry<String, Record> entry : PositionBlackboard.getRecords().entrySet()) {
        	if (!entry.getKey().equals(getName())) {
                RVOAgent neighborRVOAgent = new RVOAgent();
                neighborRVOAgent.id_ = entry.getValue().agentId;
                neighborRVOAgent.position_ = new Vector2(entry.getValue().position);
                neighborRVOAgent.velocity_ = new Vector2(entry.getValue().velocity);
                neighborRVOAgent.radius_ = (float) entry.getValue().radius;
                neighbors.put(entry.getKey(), neighborRVOAgent);
        	}
        }
    }
	
	public Point getCurrentGoal() {
		return currentGoal;
	}

	public Point getCurrentPos() {
		return rvoAgent.position_.toPoint2i();
	}

	public Vector getCurrentVelocity() {
		return rvoAgent.velocity_.toVector2d();
	}

	@Override
	protected void handleNewTask(Point task) {
				
		desiredControl = new GraphBasedOptimalPolicyController(planningGraph,
				task, ttObstaclesLessInflated, maxSpeed,
				DesiredControlNodeSearchRadius, showVis);		
		currentGoal = task;
	}


	@Override
	public EvaluatedTrajectory getCurrentTrajectory() {
		return null;
	}
	
	@Override
	protected boolean currentTaskDestinationReached() {
		return currentTask.distance(getCurrentPos()) < GOAL_REACHED_TOLERANCE;
	}
}
