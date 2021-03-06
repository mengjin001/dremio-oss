/*
 * Copyright (C) 2017-2018 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.planner.common;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.apache.arrow.vector.holders.IntHolder;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.metadata.RelMdUtil;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexNode;

import com.dremio.exec.ExecConstants;
import com.dremio.exec.planner.cost.DremioCost;
import com.dremio.exec.planner.cost.DremioCost.Factory;
import com.dremio.exec.planner.cost.RelMdRowCount;
import com.dremio.exec.planner.physical.PrelUtil;
import com.dremio.sabot.op.join.JoinUtils;
import com.dremio.sabot.op.join.JoinUtils.JoinCategory;
import com.google.common.collect.Lists;

/**
 * Base class for logical and physical Joins implemented in Dremio.
 */
public abstract class JoinRelBase extends Join {
  protected List<Integer> leftKeys = Lists.newArrayList();
  protected List<Integer> rightKeys = Lists.newArrayList();

  /**
   * The join key positions for which null values will not match. null values only match for the
   * "is not distinct from" condition.
   */
  protected List<Boolean> filterNulls = Lists.newArrayList();

  public JoinRelBase(RelOptCluster cluster, RelTraitSet traits, RelNode left, RelNode right, RexNode condition,
      JoinRelType joinType){
    super(cluster, traits, left, right, condition, joinType, Collections.<String> emptySet());
  }

  @Override
  public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery relMetadataQuery) {
    JoinCategory category;
    /*
     * for costing purpose, we don't use JoinUtils.getJoinCategory()
     * to get the join category. here we are more interested in knowing
     * if the join is equality v/s inequality/cartesian join and accordingly
     * compute the cost. JoinUtils.getJoinCategory() will split out
     * the equi-join components from join condition and can mis-categorize
     * an equality join for the purpose of computing plan cost.
     */
    if (leftKeys.size() > 0 && rightKeys.size() > 0) {
      category = JoinCategory.EQUALITY;
    } else {
      category = JoinCategory.INEQUALITY;
    }
    if (category == JoinCategory.INEQUALITY) {
      if (PrelUtil.getPlannerSettings(planner).isNestedLoopJoinEnabled()) {
        if (PrelUtil.getPlannerSettings(planner).isNlJoinForScalarOnly()) {
          if (hasScalarSubqueryInput()) {
            return computeLogicalJoinCost(planner, relMetadataQuery);
          } else {
            /*
             *  Why do we return non-infinite cost for CartsianJoin with non-scalar subquery, when LOPT planner is enabled?
             *   - We do not want to turn on the two Join permutation rule : PushJoinPastThroughJoin.LEFT, RIGHT.
             *   - As such, we may end up with filter on top of join, which will cause CanNotPlan in LogicalPlanning, if we
             *   return infinite cost.
             *   - Such filter on top of join might be pushed into JOIN, when LOPT planner is called.
             *   - Return non-infinite cost will give LOPT planner a chance to try to push the filters.
             */
            if (PrelUtil.getPlannerSettings(planner).isHepOptEnabled()) {
             return computeCartesianJoinCost(planner, relMetadataQuery);
            } else {
              return ((Factory)planner.getCostFactory()).makeInfiniteCost();
            }
          }
        } else {
          return computeLogicalJoinCost(planner, relMetadataQuery);
        }
      }
      return ((Factory)planner.getCostFactory()).makeInfiniteCost();
    }

    return computeLogicalJoinCost(planner, relMetadataQuery);
  }

  /**
   * Copied for {@link RelMdRowCount#getRowCount(Join, RelMetadataQuery)}. We will be removing this
   * function usage in Dremio code in future: TODO: DX-12150
   *
   * @param mq
   * @return
   */
  @Override
  public double estimateRowCount(RelMetadataQuery mq) {
    if (getCondition().isAlwaysTrue()) {
      return RelMdUtil.getJoinRowCount(mq, this, getCondition());
    }

    return Math.max(mq.getRowCount(getLeft()), mq.getRowCount(getRight()));
  }

  /**
   * Returns whether there are any elements in common between left and right.
   */
  private static <T> boolean intersects(List<T> left, List<T> right) {
    return new HashSet<>(left).removeAll(right);
  }

  protected boolean uniqueFieldNames(RelDataType rowType) {
    return isUnique(rowType.getFieldNames());
  }

  protected static <T> boolean isUnique(List<T> list) {
    return new HashSet<>(list).size() == list.size();
  }

  public List<Integer> getLeftKeys() {
    return this.leftKeys;
  }

  public List<Integer> getRightKeys() {
    return this.rightKeys;
  }

  protected  RelOptCost computeCartesianJoinCost(RelOptPlanner planner, RelMetadataQuery relMetadataQuery) {
    final double probeRowCount = relMetadataQuery.getRowCount(this.getLeft());
    final double buildRowCount = relMetadataQuery.getRowCount(this.getRight());

    final Factory costFactory = (Factory) planner.getCostFactory();

    final double mulFactor = 10000; // This is a magic number,
                                    // just to make sure Cartesian Join is more expensive
                                    // than Non-Cartesian Join.

    final int keySize = 1 ;  // assume having 1 join key, when estimate join cost.
    final DremioCost cost = (DremioCost) computeHashJoinCostWithKeySize(planner, keySize, relMetadataQuery).multiplyBy(mulFactor);

    // Cartesian join row count will be product of two inputs. The other factors come from the above estimated DremioCost.
    return costFactory.makeCost(
        buildRowCount * probeRowCount,
        cost.getCpu(),
        cost.getIo(),
        cost.getNetwork(),
        cost.getMemory() );

  }

  protected RelOptCost computeLogicalJoinCost(RelOptPlanner planner, RelMetadataQuery relMetadataQuery) {
    // During Logical Planning, although we don't care much about the actual physical join that will
    // be chosen, we do care about which table - bigger or smaller - is chosen as the right input
    // of the join since that is important at least for hash join and we don't currently have
    // hybrid-hash-join that can swap the inputs dynamically.  The Calcite planner's default cost of a join
    // is the same whether the bigger table is used as left input or right. In order to overcome that,
    // we will use the Hash Join cost as the logical cost such that cardinality of left and right inputs
    // is considered appropriately.
    return computeHashJoinCost(planner, relMetadataQuery);
  }

  protected RelOptCost computeHashJoinCost(RelOptPlanner planner, RelMetadataQuery relMetadataQuery) {
      return computeHashJoinCostWithKeySize(planner, this.getLeftKeys().size(), relMetadataQuery);
  }

  /**
   *
   * @param planner  : Optimization Planner.
   * @param keySize  : the # of join keys in join condition. Left key size should be equal to right key size.
   * @return         : RelOptCost
   */
  private RelOptCost computeHashJoinCostWithKeySize(RelOptPlanner planner, int keySize, RelMetadataQuery relMetadataQuery) {
    /**
     * DRILL-1023, DX-3859:  Need to make sure that join row count is calculated in a reasonable manner.  Calcite's default
     * implementation is leftRowCount * rightRowCount * discountBySelectivity, which is too large (cartesian join).
     * Since we do not support cartesian join, we should just take the maximum of the two join input row counts when
     * computing cost of the join.
     */
    double probeRowCount = relMetadataQuery.getRowCount(this.getLeft());
    double buildRowCount = relMetadataQuery.getRowCount(this.getRight());

    // cpu cost of hashing the join keys for the build side
    double cpuCostBuild = DremioCost.HASH_CPU_COST * keySize * buildRowCount;
    // cpu cost of hashing the join keys for the probe side
    double cpuCostProbe = DremioCost.HASH_CPU_COST * keySize * probeRowCount;
    // cpu cost associated with really large rows
    double cpuCostColCount = getRowType().getFieldCount() * Math.max(probeRowCount, buildRowCount);

    // cpu cost of evaluating each leftkey=rightkey join condition
    double joinConditionCost = DremioCost.COMPARE_CPU_COST * keySize;

    double factor = PrelUtil.getPlannerSettings(planner).getOptions()
        .getOption(ExecConstants.HASH_JOIN_TABLE_FACTOR_KEY).getFloatVal();
    long fieldWidth = PrelUtil.getPlannerSettings(planner).getOptions()
        .getOption(ExecConstants.AVERAGE_FIELD_WIDTH_KEY).getNumVal();

    // table + hashValues + links
    double memCost =
        (
            (fieldWidth * keySize) +
                IntHolder.WIDTH +
                IntHolder.WIDTH
        ) * buildRowCount * factor;

    double cpuCost = joinConditionCost * (probeRowCount) // probe size determine the join condition comparison cost
        + cpuCostBuild + cpuCostProbe + cpuCostColCount;

    Factory costFactory = (Factory) planner.getCostFactory();

    return costFactory.makeCost(buildRowCount + probeRowCount, cpuCost, 0, 0, memCost);
  }

  private boolean hasScalarSubqueryInput() {
    if (JoinUtils.isScalarSubquery(this.getLeft())
        || JoinUtils.isScalarSubquery(this.getRight())) {
      return true;
    }

    return false;
  }

}
