package org.janelia.saalfeldlab.regionmerging;

import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.stream.IntStream;

import org.janelia.saalfeldlab.graph.UndirectedGraph;
import org.janelia.saalfeldlab.graph.edge.Edge;
import org.janelia.saalfeldlab.graph.edge.EdgeMerger;
import org.janelia.saalfeldlab.graph.edge.EdgeWeight;
import org.janelia.saalfeldlab.util.unionfind.HashMapStoreUnionFind;
import org.janelia.saalfeldlab.util.unionfind.Stringify;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import gnu.trove.iterator.TLongIntIterator;
import gnu.trove.list.array.TDoubleArrayList;
import gnu.trove.list.array.TLongArrayList;
import gnu.trove.map.hash.TLongIntHashMap;
import gnu.trove.set.hash.TIntHashSet;
import gnu.trove.set.hash.TLongHashSet;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;

public class RegionMerging
{

	public static Logger LOG = LoggerFactory.getLogger( MethodHandles.lookup().lookupClass() );

	public static final int MERGES_LOG_EDGE_INDEX_OFFSET = 0;

	public static final int MERGES_LOG_WEIGHT_OFFSET = 1;

	public static final int MERGES_LOG_FROM_OFFSET = 2;

	public static final int MERGES_LOG_TO_OFFSET = 3;

	public static final int MERGES_LOG_STEP_SIZE = 4;

	public static Pair< TLongArrayList, HashMapStoreUnionFind > mergeLocallyMinimalEdges(
			final UndirectedGraph g,
			final EdgeMerger merger,
			final EdgeWeight edgeWeight,
			final double threshold )
	{
		return mergeLocallyMinimalEdges( g, merger, edgeWeight, threshold, 0, new TIntHashSet() );
	}

	public static Pair< TLongArrayList, HashMapStoreUnionFind > mergeLocallyMinimalEdges(
			final UndirectedGraph g,
			final EdgeMerger merger,
			final EdgeWeight edgeWeight,
			final double threshold,
			final int minimumMultiplicity,
			final TIntHashSet nonContractingEdges )
	{
		final TDoubleArrayList edges = g.edges();
		final Edge e1 = new Edge( edges, merger.dataSize() );
		final Edge e2 = new Edge( edges, merger.dataSize() );

		final HashMapStoreUnionFind regionMapping = new HashMapStoreUnionFind();

		final TLongArrayList merges = new TLongArrayList();

//		for ( int k = 0; k < e1.size(); ++k )
//		{
//			e1.setIndex( k );
//			e1.weight( edgeWeight.weight( e1, counts.get( e1.from() ), counts.get( e1.to() ) ) );
//		}

		boolean changed = true;
		int iteration = 1;
		while ( changed )
		{

			changed = false;

			for ( int k = 0; k < e1.size(); ++k )
			{
				e1.setIndex( k );
				if ( e1.isValid() && e1.isStale() )
				{
					e1.weight( edgeWeight.weight( e1 ) );
					e1.setActive();
				}
				else if ( e1.isObsolete() )
					e1.weight( Double.POSITIVE_INFINITY );
			}
			final boolean[] localMinimum = new boolean[ e1.size() ];
			Arrays.fill( localMinimum, true );
			final boolean[] isInPlateau = new boolean[ e1.size() ];
			final boolean[] isNeighborOfNonContractable = new boolean[ e1.size() ];

			final HashMapStoreUnionFind plateausUnionFind = new HashMapStoreUnionFind();

			findMinimaAndPlateaus( g, localMinimum, isInPlateau, isNeighborOfNonContractable, nonContractingEdges, e1, e2, plateausUnionFind, threshold, minimumMultiplicity );

			final boolean[] isValidPlateau = new boolean[ e1.size() ];
			Arrays.fill( isValidPlateau, true );
			final TLongHashSet plateauRoots = new TLongHashSet();

			for ( int k = 0; k < localMinimum.length; ++k )
			{
				e1.setIndex( k );
				if ( e1.isObsolete() )
					continue;
				final boolean isMinimum = localMinimum[ k ];
				final boolean isPlateau = isInPlateau[ k ];
				final boolean isContractible = !nonContractingEdges.contains( k );
				final boolean noNonContractibleNeighbor = !isNeighborOfNonContractable[ k ];
				if ( isMinimum && !isPlateau )
				{
					if ( isContractible && noNonContractibleNeighbor && e1.multiplicity() > minimumMultiplicity && mergeEdge( g, e1, k, regionMapping, merger, merges ) )
						changed = true;
				}
				else if ( isPlateau )
				{
					final long root = plateausUnionFind.findRoot( k );
					// no &&= in java
					isValidPlateau[ ( int ) root ] &= isMinimum && isContractible && noNonContractibleNeighbor;
					if ( !plateauRoots.contains( root ) )
						plateauRoots.add( root );
				}
			}

			for ( int k = 0; k < localMinimum.length; ++k )

				if ( isInPlateau[ k ] )
				{
					final long root = plateausUnionFind.findRoot( k );
					if ( isValidPlateau[ ( int ) root ] )
					{
						e1.setIndex( k );
						if ( e1.isValid() && e1.multiplicity() > minimumMultiplicity && mergeEdge( g, e1, k, regionMapping, merger, merges ) )
							changed = true;
					}
				}

			LOG.debug(
					"Finished iteration {} with {} local minima and {} plateau edges.",
					iteration,
					new Stringify( () -> "" + IntStream.range( 0, localMinimum.length ).mapToObj( i -> localMinimum[ i ] ).filter( m -> m ).count() ),
					new Stringify( () -> "" + IntStream.range( 0, localMinimum.length ).mapToObj( i -> isInPlateau[ i ] ).filter( m -> m ).count() ) );

			++iteration;
		}

		return new ValuePair<>( merges, regionMapping );

	}

	public static void findMinimaAndPlateaus(
			final UndirectedGraph g,
			final boolean[] localMinimum,
			final boolean[] isInPlateau,
			final boolean[] isNeighborOfNonContractable,
			final TIntHashSet nonConctractableEdges,
			final Edge e1,
			final Edge e2,
			final HashMapStoreUnionFind plateausUnionFind,
			final double threshold,
			final int minimumMultiplicity )
	{

		final long[] connectedNodes = new long[ 2 ];

		for ( int k = 0; k < e1.size(); ++k )
		{
			e1.setIndex( k );
			if ( e1.isObsolete() )
			{
				localMinimum[ k ] = false;
				continue;
			}

			plateausUnionFind.findRoot( k );

			final double w = e1.weight();
			if ( w > threshold )
			{
				localMinimum[ k ] = false;
				continue;
			}
			connectedNodes[ 0 ] = e1.from();
			connectedNodes[ 1 ] = e1.to();

			boolean isPlateau = false;

			double minimumValue = Double.MAX_VALUE;

			for ( final long nodeId : connectedNodes )
			{
				final TLongIntHashMap neighboringEdges = g.nodeEdgeMap().get( nodeId );
				for ( final TLongIntIterator it = neighboringEdges.iterator(); it.hasNext(); )
				{
					it.advance();
					final int otherEdgeIndex = it.value();

					if ( otherEdgeIndex == k )
						continue;

					if ( nodeId == it.key() )
						continue;

					e2.setIndex( otherEdgeIndex );
					if ( e2.multiplicity() < minimumMultiplicity )
						continue;

					final double w2 = e2.weight();

					minimumValue = Math.min( w2, minimumValue );

					if ( nonConctractableEdges.contains( otherEdgeIndex ) )
						isNeighborOfNonContractable[ k ] = true;

					if ( w == w2 )
					{
						isPlateau = true;
						plateausUnionFind.join( plateausUnionFind.findRoot( k ), plateausUnionFind.findRoot( otherEdgeIndex ) );
					}

				}
			}

			localMinimum[ k ] = w <= minimumValue;
			isInPlateau[ k ] = isPlateau;
		}
	}

	public static boolean mergeEdge(
			final UndirectedGraph g,
			final Edge e,
			final long index,
			final HashMapStoreUnionFind regionMapping,
			final EdgeMerger merger,
			final TLongArrayList merges )
	{
		final long r1 = regionMapping.findRoot( e.from() );
		final long r2 = regionMapping.findRoot( e.to() );
		if ( r1 == r2 )
			return false;

		final long newNode = regionMapping.join( r1, r2 );

		merges.add( index );
		merges.add( Double.doubleToRawLongBits( e.weight() ) );
		merges.add( r1 );
		merges.add( r2 );

		g.contract( e, newNode, r1, r2, merger );
		return true;
	}

}
