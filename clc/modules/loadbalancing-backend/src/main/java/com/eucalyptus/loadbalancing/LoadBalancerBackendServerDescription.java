/*************************************************************************
 * Copyright 2009-2015 Eucalyptus Systems, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 *
 * Please contact Eucalyptus Systems, Inc., 6755 Hollister Ave., Goleta
 * CA 93117, USA or visit http://www.eucalyptus.com/licenses/ if you need
 * additional information or have any questions.
 ************************************************************************/
package com.eucalyptus.loadbalancing;

import java.util.List;
import java.util.NoSuchElementException;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.JoinTable;
import javax.persistence.ManyToMany;
import javax.persistence.ManyToOne;
import javax.persistence.PersistenceContext;
import javax.persistence.PostLoad;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import javax.persistence.Transient;

import org.apache.log4j.Logger;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;

import com.eucalyptus.entities.AbstractPersistent;
import com.eucalyptus.entities.Entities;
import com.eucalyptus.entities.TransactionResource;
import com.eucalyptus.loadbalancing.LoadBalancerPolicyDescription.LoadBalancerPolicyDescriptionCoreView;
import com.eucalyptus.loadbalancing.LoadBalancerPolicyDescription.LoadBalancerPolicyDescriptionCoreViewTransform;
import com.eucalyptus.util.Exceptions;
import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

/**
 * @author Sang-Min Park
 *
 */
@Entity
@PersistenceContext( name = "eucalyptus_loadbalancing" )
@Table( name = "metadata_backend_server_description" )
@Cache( usage = CacheConcurrencyStrategy.TRANSACTIONAL )
public class LoadBalancerBackendServerDescription extends AbstractPersistent {
  private static Logger    LOG     = Logger.getLogger( LoadBalancerBackendServerDescription.class );
  
  @Transient
  private static final long serialVersionUID = 1L;
  
  @Transient
  private LoadBalancerBackendServerDescriptionRelationView view = null;
  
  @ManyToOne
  @JoinColumn( name = "metadata_loadbalancer_fk", nullable=false )
  @Cache( usage = CacheConcurrencyStrategy.TRANSACTIONAL )
  private LoadBalancer loadbalancer = null;
  
  @Column( name = "instance_port", nullable=false)
  private Integer instancePort = null;
  
  @Column(name="unique_name", nullable=false, unique=true)
  private String uniqueName = null;
 
  @ManyToMany( fetch = FetchType.LAZY, cascade = CascadeType.REMOVE )
  @JoinTable( name = "metadata_policy_set_for_backends", joinColumns = { @JoinColumn( name = "metadata_backend_fk" ) },  inverseJoinColumns = @JoinColumn( name = "metadata_policy_fk" ) )
  @Cache( usage= CacheConcurrencyStrategy.TRANSACTIONAL )
  private List<LoadBalancerPolicyDescription> policyDescriptions = null;
  
  private LoadBalancerBackendServerDescription() { }
  
  public LoadBalancerBackendServerDescription(final LoadBalancer lb, final int instancePort){
    this.loadbalancer = lb;
    this.instancePort = instancePort;
  }
  
  public LoadBalancerBackendServerDescription(final LoadBalancer lb, final int instancePort, 
      final List<LoadBalancerPolicyDescription> policyDescriptions){
    this(lb, instancePort);
    this.policyDescriptions = policyDescriptions;
  }
  
  public static LoadBalancerBackendServerDescription named(final LoadBalancer lb, final int instancePort){
    final LoadBalancerBackendServerDescription backend = new LoadBalancerBackendServerDescription(lb, instancePort);
    backend.uniqueName = backend.createUniqueName();
    return backend;
  }
  
  public Integer getInstancePort(){
    return this.instancePort;
  }
  
  public List<LoadBalancerPolicyDescriptionCoreView> getPolicyDescriptions() {
    return this.view.getPolicyDescriptions();
  }
  
  public void addPolicy(final LoadBalancerPolicyDescription policy){
    if(this.policyDescriptions==null){
      this.policyDescriptions = Lists.newArrayList();
    }
    if(!this.policyDescriptions.contains(policy))
      this.policyDescriptions.add(policy);
  }
  
  public void removePolicy(final String policyName){
    if(this.policyDescriptions==null || policyName==null)
      return;
    LoadBalancerPolicyDescription toDelete = null;
    for(final LoadBalancerPolicyDescription pol : this.policyDescriptions){
      if(policyName.equals(pol.getPolicyName()))
        toDelete = pol;
    }
    if(toDelete!=null)
      this.policyDescriptions.remove(toDelete);
  }
  
  public void removePolicy(final LoadBalancerPolicyDescription policy){
    if(this.policyDescriptions==null || policy==null)
      return;
    this.policyDescriptions.remove(policy);
  }

  @PostLoad
  private void onLoad(){
    if(this.view==null)
      this.view = new LoadBalancerBackendServerDescriptionRelationView(this);
  }
  
  @PrePersist
  private void generateOnCommit( ) {
    if(this.uniqueName==null)
      this.uniqueName = createUniqueName( );
  }

  protected String createUniqueName( ) {
    return String.format("backend-server-%s-%s-%d", this.loadbalancer.getOwnerAccountNumber(), 
        this.loadbalancer.getDisplayName(), 
        this.instancePort);
  }
  
  public String getUniqueName(){
    return this.uniqueName;
  }
  
  @Override
  public boolean equals(final Object obj){
    if ( this == obj ) {
      return true;
    }
    if ( obj == null ) {
      return false;
    }
    if ( getClass( ) != obj.getClass( ) ) {
      return false;
    }
    final LoadBalancerBackendServerDescription other = (LoadBalancerBackendServerDescription) obj;
    if(this.loadbalancer==null){
      if( other.loadbalancer!=null){
        return false;
      }
    }else if(!this.loadbalancer.equals(other.loadbalancer)){
      return false;
    }
    
    if ( this.instancePort == null ) {
      if ( other.instancePort != null ) {
        return false;
      }
    } else if ( this.instancePort != other.instancePort ) {
      return false;
    }
    
    return true;
  }
  
  
  @Override
  public int hashCode(){
    final int prime = 31;
    int result = 1;

    result = prime * result +  ( ( this.loadbalancer == null )
      ? 0
      : this.loadbalancer.hashCode( ) );
    
    result = prime * result + ( ( this.instancePort == null )
      ? 0
      : this.instancePort.hashCode( ) );
    return result;
  }
  
  @Override
  public String toString(){
    return String.format("[%s] Backend Server Description - instance port: %d", 
        this.loadbalancer, this.instancePort);
  }
  
  
  public static class LoadBalancerBackendServerDescriptionCoreView{
    private LoadBalancerBackendServerDescription backendDesc = null;
    
    public LoadBalancerBackendServerDescriptionCoreView(LoadBalancerBackendServerDescription desc) {
      this.backendDesc = desc;
    }
    
    public Integer getInstancePort() {
      return this.backendDesc.instancePort;
    }
  }
  
  public enum LoadBalancerBackendServerDescriptionCoreViewTransform implements 
    Function<LoadBalancerBackendServerDescription, LoadBalancerBackendServerDescriptionCoreView>
  {
    INSTANCE;

    @Override
    public LoadBalancerBackendServerDescriptionCoreView apply(
        LoadBalancerBackendServerDescription arg0) {
      return new LoadBalancerBackendServerDescriptionCoreView(arg0);
    }
  }

  public static class LoadBalancerBackendServerDescriptionRelationView{
    private LoadBalancerBackendServerDescription backendServerDesc = null;
    private ImmutableList<LoadBalancerPolicyDescriptionCoreView> policyDesc = null;
    LoadBalancerBackendServerDescriptionRelationView(final LoadBalancerBackendServerDescription desc){
      this.backendServerDesc = desc;
      if(desc.policyDescriptions != null)
        this.policyDesc = ImmutableList.copyOf(Collections2.transform(desc.policyDescriptions,
            LoadBalancerPolicyDescriptionCoreViewTransform.INSTANCE));
    }
    
    public ImmutableList<LoadBalancerPolicyDescriptionCoreView> getPolicyDescriptions(){
      return this.policyDesc;
    }
  }
  

  public enum LoadBalancerBackendServerDescriptionEntityTransform implements
    Function<LoadBalancerBackendServerDescriptionCoreView, LoadBalancerBackendServerDescription>{
    INSTANCE;

    @Override
    public LoadBalancerBackendServerDescription apply(
        LoadBalancerBackendServerDescriptionCoreView arg0) {
      try ( final TransactionResource db = Entities.transactionFor( LoadBalancerBackendServerDescription.class ) ) {
        return Entities.uniqueResult(arg0.backendDesc);
      }catch(final NoSuchElementException ex){
        throw ex;
      }catch (final Exception ex) {
        throw Exceptions.toUndeclared(ex);
      }
    }
  }
}
