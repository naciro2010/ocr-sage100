import { lazy, type ComponentType, type LazyExoticComponent } from 'react'

type Loader<P = object> = () => Promise<{ default: ComponentType<P> }>

export interface LazyPage<P = object> {
  Component: LazyExoticComponent<ComponentType<P>>
  preload: Loader<P>
}

function makePage<P = object>(loader: Loader<P>): LazyPage<P> {
  let cached: Promise<{ default: ComponentType<P> }> | null = null
  const preload: Loader<P> = () => {
    if (!cached) cached = loader()
    return cached
  }
  return { Component: lazy(preload), preload }
}

export const Dashboard = makePage(() => import('../pages/Dashboard'))
export const DossierList = makePage(() => import('../pages/DossierList'))
export const DossierDetail = makePage(() => import('../pages/DossierDetail'))
export const EngagementList = makePage(() => import('../pages/EngagementList'))
export const EngagementDetail = makePage(() => import('../pages/EngagementDetail'))
export const EngagementUpload = makePage(() => import('../pages/EngagementUpload'))
export const EngagementNew = makePage(() => import('../pages/EngagementNew'))
export const FournisseurList = makePage(() => import('../pages/FournisseurList'))
export const FournisseurDetail = makePage(() => import('../pages/FournisseurDetail'))
export const Settings = makePage(() => import('../pages/Settings'))
export const Finalize = makePage(() => import('../pages/Finalize'))
export const ClaudeUsage = makePage(() => import('../pages/ClaudeUsage'))
export const RulesHealth = makePage(() => import('../pages/RulesHealth'))
export const NotFound = makePage(() => import('../pages/NotFound'))
