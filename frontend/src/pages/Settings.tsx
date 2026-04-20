import { useState, useEffect, useCallback } from 'react'
import {
  getAiSettings, saveAiSettings as saveAiSettingsApi,
  getOcrSettings, saveOcrSettings as saveOcrSettingsApi,
  getSystemHealth,
} from '../api/client'
import type {
  AiSettingsResponse, OcrSettingsResponse,
  SystemHealthResponse, HealthComponent, HealthTone,
} from '../api/types'
import { useToast } from '../components/Toast'
import { ALL_RULES, getDisabledRules, setDisabledRules } from '../config/validationRules'
import {
  Settings as SettingsIcon, Brain, ScanLine, Cpu,
  CheckCircle, XCircle, Loader2, Eye, EyeOff,
  Shield, Globe, Key, Info, ShieldCheck,
  FileText, Layers, Zap, Sparkles, CircuitBoard,
  ArrowRight, Database, Server, Activity, RefreshCw,
  HardDrive, Clock, AlertTriangle,
} from 'lucide-react'

const AI_MODELS = [
  { value: 'claude-opus-4-7', label: 'Claude Opus 4.7', desc: 'Le plus precis, recommande pour dossiers' },
  { value: 'claude-sonnet-4-6', label: 'Claude Sonnet 4.6', desc: 'Rapide, bon compromis' },
  { value: 'claude-haiku-4-5-20251001', label: 'Claude Haiku 4.5', desc: 'Leger, economique' },
]

const MISTRAL_OCR_MODELS = [
  { value: 'mistral-ocr-latest', label: 'mistral-ocr-latest', desc: 'Derniere version, Markdown + tableaux' },
]

const SHORTCUTS = [
  { keys: 'Ctrl+K', desc: 'Recherche globale' },
  { keys: 'Esc', desc: 'Fermer modale/recherche' },
]

export default function Settings() {
  const { toast } = useToast()
  const [activeTab, setActiveTab] = useState<'pipeline' | 'cles' | 'health' | 'rules' | 'about'>('pipeline')
  const [aiSettings, setAiSettings] = useState<AiSettingsResponse | null>(null)
  const [aiEnabled, setAiEnabled] = useState(false)
  const [aiApiKey, setAiApiKey] = useState('')
  const [aiModel, setAiModel] = useState('claude-opus-4-7')
  const [aiBaseUrl, setAiBaseUrl] = useState('https://api.anthropic.com')
  const [showApiKey, setShowApiKey] = useState(false)
  const [aiSaving, setAiSaving] = useState(false)

  const [ocrSettings, setOcrSettings] = useState<OcrSettingsResponse | null>(null)
  const [mistralEnabled, setMistralEnabled] = useState(false)
  const [mistralApiKey, setMistralApiKey] = useState('')
  const [mistralModel, setMistralModel] = useState('mistral-ocr-latest')
  const [mistralBaseUrl, setMistralBaseUrl] = useState('https://api.mistral.ai')
  const [showMistralKey, setShowMistralKey] = useState(false)
  const [ocrSaving, setOcrSaving] = useState(false)

  useEffect(() => {
    getAiSettings()
      .then(data => {
        setAiSettings(data)
        setAiEnabled(data.enabled)
        setAiApiKey(data.apiKeyConfigured ? data.apiKey : '')
        setAiModel(data.model)
        setAiBaseUrl(data.baseUrl)
      })
      .catch(() => {})

    getOcrSettings()
      .then(data => {
        setOcrSettings(data)
        setMistralEnabled(data.mistralEnabled)
        setMistralApiKey(data.mistralApiKeyConfigured ? data.mistralApiKey : '')
        setMistralModel(data.mistralModel)
        setMistralBaseUrl(data.mistralBaseUrl)
      })
      .catch(() => {})
  }, [])

  const handleSaveAi = async () => {
    setAiSaving(true)
    try {
      const result = await saveAiSettingsApi({
        enabled: aiEnabled, apiKey: aiApiKey || undefined,
        model: aiModel, baseUrl: aiBaseUrl,
      })
      setAiSettings(result)
      toast('success', 'Configuration IA sauvegardee')
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Erreur')
    } finally { setAiSaving(false) }
  }

  const handleSaveOcr = async () => {
    setOcrSaving(true)
    try {
      const result = await saveOcrSettingsApi({
        mistralEnabled,
        mistralApiKey: mistralApiKey || undefined,
        mistralModel,
        mistralBaseUrl,
      })
      setOcrSettings(result)
      toast('success', 'Configuration OCR sauvegardee')
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Erreur')
    } finally { setOcrSaving(false) }
  }

  const mistralLive = Boolean(ocrSettings?.mistralEnabled && ocrSettings?.mistralApiKeyConfigured)
  const aiLive = Boolean(aiSettings?.enabled && aiSettings?.apiKeyConfigured)

  return (
    <div>
      <div className="page-header"><h1><SettingsIcon size={22} /> Parametres</h1></div>

      <div className="settings-tabs" role="tablist" aria-label="Onglets parametres">
        {([
          ['pipeline', 'Pipeline'],
          ['cles', 'Cles API'],
          ['health', 'Etat systeme'],
          ['rules', 'Regles'],
          ['about', 'A propos'],
        ] as const).map(([key, label]) => (
          <button key={key}
            role="tab"
            aria-selected={activeTab === key}
            aria-controls={`tab-panel-${key}`}
            className={`btn ${activeTab === key ? 'btn-primary' : 'btn-secondary'}`}
            onClick={() => setActiveTab(key)}>
            {label}
          </button>
        ))}
      </div>

      {/* =========================================================== */}
      {/* PIPELINE TAB — parcours d'un document de bout en bout        */}
      {/* =========================================================== */}
      {activeTab === 'pipeline' && (
        <div role="tabpanel" id="tab-panel-pipeline">
          <SettingsHero
            eyebrow="Architecture · parcours d'un document"
            title={<>De l'upload a la validation, <span style={{ color: 'var(--accent-deep)' }}>cinq etapes</span> — et un service cloud uniquement quand c'est necessaire.</>}
            icon={<Layers size={24} aria-hidden="true" />}
            lead="Un document n'emprunte jamais la meme route. Si le PDF est numerique, Tika extrait le texte gratuitement et Claude travaille dessus. Si c'est un scan, Mistral prend le relai. Les fichiers originaux restent dans S3 chez vous, seul le texte necessaire transite — et le moteur de regles finit le travail en local."
            status="active"
            statusLabel="Pipeline operationnel"
            kpi="5"
            kpiLabel="Etapes"
          />

          <div className="card">
            <div className="section-title-rail">Histoire d'un document</div>
            <div className="howto-steps">
              <div className="howto-step">
                <div className="howto-step-num">01</div>
                <div>
                  <div className="howto-step-title">
                    Upload &amp; stockage
                    <span className="pill-meta">multipart · 50 Mo max</span>
                    <span className="pill-meta accent">S3</span>
                  </div>
                  <div className="howto-step-desc">
                    Le PDF ou l'image arrive sur <code style={codeStyle}>POST /api/dossiers/&#123;id&#125;/documents</code>.
                    Le binaire est pousse <strong>immediatement</strong> dans le bucket S3 sous la cle
                    {' '}<code style={codeStyle}>dossiers/&#123;id&#125;/&#123;timestamp&#125;_&#123;fichier&#125;</code>.
                    PostgreSQL ne recoit qu'un pointeur — l'octet brut reste dans l'objet-store.
                  </div>
                </div>
              </div>
              <div className="howto-step">
                <div className="howto-step-num">02</div>
                <div>
                  <div className="howto-step-title">
                    Cascade OCR
                    <span className="pill-meta">local d'abord</span>
                    <span className="pill-meta accent">seuil 200 mots</span>
                  </div>
                  <div className="howto-step-desc">
                    <strong>Tika tente toujours en premier</strong> et extrait le texte natif du PDF.
                    Si <strong>≥ 200 mots</strong> sont trouves, le PDF est considere numerique et la cascade s'arrete la (0 $).
                    Sinon c'est un scan : <strong>Mistral OCR</strong> prend le relai s'il est configure
                    (~0.001 $/page, Markdown propre FR + AR), sinon <strong>Tesseract local</strong> (0 $) joue le filet de securite.
                    Mistral et Tesseract sont exclusifs — jamais les deux a la fois.
                  </div>
                </div>
              </div>
              <div className="howto-step">
                <div className="howto-step-num">03</div>
                <div>
                  <div className="howto-step-title">
                    Extraction Claude
                    <span className="pill-meta">2 appels</span>
                    <span className="pill-meta accent">texte uniquement</span>
                  </div>
                  <div className="howto-step-desc">
                    Le texte OCR — <strong>jamais le PDF binaire</strong> — est envoye a l'API Anthropic en TLS 1.3.
                    Appel 1 : classification (facture, BC, OP, contrat, PV, attestation...).
                    Appel 2 : extraction JSON structuree (montants HT/TVA/TTC, ICE, IF, RIB, lignes, retenues).
                    Un 3e appel n'est declenche que si la confiance est &lt; 0.69.
                  </div>
                </div>
              </div>
              <div className="howto-step">
                <div className="howto-step-num">04</div>
                <div>
                  <div className="howto-step-title">
                    Persistance
                    <span className="pill-meta">Postgres + S3</span>
                    <span className="pill-meta accent">binaires separes</span>
                  </div>
                  <div className="howto-step-desc">
                    PostgreSQL stocke les metadonnees, les donnees extraites (JSONB), les resultats de validation et l'audit.
                    Les fichiers binaires et le texte OCR brut restent dans S3 pour garder les lignes Postgres legeres.
                    Schema gere par Flyway, JPA en <code style={codeStyle}>ddl-auto: validate</code>.
                  </div>
                </div>
              </div>
              <div className="howto-step">
                <div className="howto-step-num">05</div>
                <div>
                  <div className="howto-step-title">
                    Validation
                    <span className="pill-meta">local</span>
                    <span className="pill-meta">0 $</span>
                    <span className="pill-meta">{'< 100 ms'}</span>
                  </div>
                  <div className="howto-step-desc">
                    Le ValidationEngine croise les donnees extraites entre facture, bon de commande et ordre de paiement.
                    22 regles deterministes (R01-R20 + CK01-CK10) executees en memoire. Aucun appel externe a cette etape.
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div className="card">
            <div className="section-title-rail">Le pipeline en un coup d'oeil</div>
            <div className="pipeline-flow">
              <PipelineNode
                tag="IN"
                name="Upload"
                role="PDF ou image, 50 Mo max. Binaire pousse dans S3 immediatement."
                meta="multipart · S3"
                badge="Entry point"
                state="active"
                icon={<ArrowRight size={16} />}
              />
              <PipelineNode
                tag="01"
                name="Apache Tika"
                role="Extraction du texte natif. Decision sur le seuil de 200 mots."
                meta="local · instantane · 0 $"
                badge="Toujours actif"
                state="active"
                icon={<FileText size={16} />}
              />
              <PipelineNode
                tag="02"
                name={mistralLive ? 'Mistral OCR' : 'Tesseract 5'}
                role={mistralLive
                  ? 'API cloud — Markdown structure pour scans, FR + AR natif'
                  : 'OCR local (fra + ara + eng), filet de securite'}
                meta={mistralLive ? 'cloud · ~0.001 $/page' : 'local · 1-2 s/page · 0 $'}
                badge={mistralLive ? 'Actif pour scans' : 'Actif pour scans'}
                state={mistralLive ? 'active' : 'fallback'}
                icon={mistralLive ? <Sparkles size={16} /> : <CircuitBoard size={16} />}
              />
              <PipelineNode
                tag="03"
                name="Claude (Anthropic)"
                role="Classification + extraction JSON. Texte OCR uniquement, jamais le PDF."
                meta="2 appels · ~3k-8k tokens"
                badge={aiLive ? 'Configure' : 'En veille'}
                state={aiLive ? 'active' : 'off'}
                icon={<Brain size={16} />}
              />
              <PipelineNode
                tag="04"
                name="Postgres + S3"
                role="Metadata et JSONB en base, binaires et texte OCR en S3"
                meta="Flyway · V1-V13"
                badge="Source de verite"
                state="active"
                icon={<Database size={16} />}
              />
              <PipelineNode
                tag="05"
                name="ValidationEngine"
                role="22 regles croisees (R01-R20 + CK01-CK10), en local"
                meta="deterministe · < 100 ms"
                badge="Local"
                state="active"
                icon={<Zap size={16} />}
              />
            </div>
          </div>

          <div className="card">
            <div className="section-title-rail">3 parcours concrets</div>
            <div className="scenario-grid">
              <div className="scenario-card">
                <div className="scenario-card-head"><span>CAS 1</span><FileText size={12} /></div>
                <div className="scenario-card-title">Facture PDF numerique</div>
                <div className="scenario-card-desc">
                  Generee par SAP / Sage / Excel. Tika lit le texte natif en 100 ms, depasse les 200 mots,
                  l'OCR cloud n'est jamais appele. Cout OCR : 0 $.
                </div>
                <div className="scenario-card-route">
                  TIKA <span className="arrow">→</span> CLAUDE
                </div>
              </div>
              <div className="scenario-card">
                <div className="scenario-card-head"><span>CAS 2</span><Layers size={12} /></div>
                <div className="scenario-card-title">PDF avec tableaux</div>
                <div className="scenario-card-desc">
                  Lignes HT/TVA/TTC, grilles tarifaires. PdfMarkdownExtractor rend la structure
                  en Markdown, Claude extrait ligne par ligne sans se tromper.
                </div>
                <div className="scenario-card-route">
                  TIKA <span className="arrow">→</span> MARKDOWN <span className="arrow">→</span> CLAUDE
                </div>
              </div>
              <div className="scenario-card">
                <div className="scenario-card-head"><span>CAS 3</span><ScanLine size={12} /></div>
                <div className="scenario-card-title">Scan papier ou photo</div>
                <div className="scenario-card-desc">
                  Attestation fiscale scannee, cachet, signature. Tika rend moins de 200 mots.
                  {mistralLive
                    ? ' Mistral renvoie du Markdown propre, meme en arabe.'
                    : ' Tesseract prend le relai en local, precision moindre mais gratuit.'}
                </div>
                <div className="scenario-card-route">
                  TIKA <span className="arrow">→</span> {mistralLive ? 'MISTRAL' : 'TESSERACT'} <span className="arrow">→</span> CLAUDE
                </div>
              </div>
            </div>
          </div>

          <div className="card">
            <div className="section-title-rail">
              Confidentialite — ce qui sort, ce qui reste
              <span style={{
                marginLeft: 'auto',
                fontFamily: 'var(--font-mono)', fontSize: 10,
                letterSpacing: 0.5, textTransform: 'none',
                color: 'var(--ink-40)', fontWeight: 500,
              }}>
                schema simplifie
              </span>
            </div>
            <div
              className="flow-diagram"
              aria-label="Schema du flux de donnees"
              style={{ gridTemplateColumns: '1fr 70px 1fr' }}
            >
              <div className="flow-zone flow-zone-internal">
                <span className="flow-zone-tag">Chez vous</span>
                <h5>Infrastructure MADAEF</h5>
                <div className="flow-zone-sub">Backend Kotlin, PostgreSQL, bucket S3, moteur de regles.</div>
                <ul>
                  <li>Fichiers PDF &amp; images originaux (S3)</li>
                  <li>Donnees extraites et validations (Postgres)</li>
                  <li>Tika, Tesseract, regles R01-R20 + CK01-CK10</li>
                </ul>
                <div className="flow-zone-meta">Source de verite</div>
              </div>

              <div className="flow-arrow" aria-hidden="true">
                <span className="flow-arrow-label">TLS 1.3</span>
              </div>

              <div className="flow-zone flow-zone-external">
                <span className="flow-zone-tag">Cote cloud</span>
                <h5>APIs Anthropic &amp; Mistral</h5>
                <div className="flow-zone-sub">Traitement stateless, pas de conservation longue.</div>
                <ul>
                  <li>Texte OCR uniquement (jamais le PDF binaire)</li>
                  <li>Prompts d'extraction (system)</li>
                  <li>Pas d'entrainement sur vos appels</li>
                  <li>Retention safety ≤ 30 jours puis suppression</li>
                </ul>
                <div className="flow-zone-meta">Retour · JSON structure</div>
              </div>
            </div>

            <div className="privacy-grid" style={{ marginTop: 14 }}>
              <div className="privacy-card">
                <div className="privacy-card-icon"><Shield size={16} /></div>
                <div className="privacy-card-title">Pas d'entrainement</div>
                <div className="privacy-card-desc">Les donnees envoyees via API ne servent pas a entrainer les modeles Anthropic ou Mistral.</div>
                <div className="privacy-card-source">Politiques API officielles</div>
              </div>
              <div className="privacy-card">
                <div className="privacy-card-icon"><Key size={16} /></div>
                <div className="privacy-card-title">Chiffrement TLS 1.3</div>
                <div className="privacy-card-desc">Toutes les requetes sortantes sont chiffrees de bout en bout.</div>
                <div className="privacy-card-source">Standard HTTPS / TLS</div>
              </div>
              <div className="privacy-card">
                <div className="privacy-card-icon"><Clock size={16} /></div>
                <div className="privacy-card-title">Retention ≤ 30 jours</div>
                <div className="privacy-card-desc">Anthropic et Mistral conservent les requetes max 30 j pour la securite, puis suppression automatique.</div>
                <div className="privacy-card-source">Data usage policy</div>
              </div>
              <div className="privacy-card">
                <div className="privacy-card-icon"><Globe size={16} /></div>
                <div className="privacy-card-title">Conforme RGPD</div>
                <div className="privacy-card-desc">Pas de donnees nominatives sensibles transmises. Fichiers sources stockes en UE.</div>
                <div className="privacy-card-source">DPA disponible</div>
              </div>
            </div>

            <div className="alert alert-info" style={{ marginTop: 14 }}>
              <Info size={14} style={{ flexShrink: 0 }} aria-hidden="true" />
              <span>
                Mode <strong>100 % local</strong> possible : desactivez Claude et Mistral dans l'onglet <strong>Cles API</strong>.
                Tika + Tesseract + les 22 regles restent operationnels, au prix d'une precision moindre sur les documents complexes.
                Suivi detaille tokens &amp; couts par dossier : page <strong>Usage Claude</strong> du menu lateral.
              </span>
            </div>
          </div>
        </div>
      )}

      {/* =========================================================== */}
      {/* CLES API TAB — Claude + Mistral regroupes                    */}
      {/* =========================================================== */}
      {activeTab === 'cles' && (
        <div role="tabpanel" id="tab-panel-cles">
          <SettingsHero
            eyebrow="Deux services cloud · deux cles"
            title={<>Une seule page pour brancher <span style={{ color: 'var(--accent-deep)' }}>Claude</span> et <span style={{ color: 'var(--accent-deep)' }}>Mistral</span>.</>}
            icon={<Key size={24} aria-hidden="true" />}
            lead="Claude fait l'extraction metier (classification + JSON structure). Mistral fait l'OCR premium sur les scans. Les deux sont optionnels — sans cle, la plateforme bascule sur Tika + Tesseract + regles locales. Les cles saisies ici prennent le pas sur les variables d'environnement et sont persistees en base."
            status={aiLive && mistralLive ? 'active' : (aiLive || mistralLive ? 'idle' : 'off')}
            statusLabel={
              aiLive && mistralLive ? 'Claude + Mistral en production'
                : aiLive ? 'Claude uniquement'
                : mistralLive ? 'Mistral uniquement'
                : 'Aucun service cloud actif'
            }
            kpi={`${(aiLive ? 1 : 0) + (mistralLive ? 1 : 0)}/2`}
            kpiLabel="Services branches"
          />

          <div className={`status-banner ${aiLive || mistralLive ? 'tone-active' : 'tone-fallback'}`}>
            <span className="status-banner-dot" aria-hidden="true" />
            <div>
              <div className="status-banner-title">
                {aiLive && mistralLive
                  ? 'Claude et Mistral sont actifs — precision maximale sur scans et documents complexes'
                  : aiLive
                    ? 'Claude actif, Mistral non configure — Tesseract prendra le relai sur les scans'
                    : mistralLive
                      ? 'Mistral actif, Claude non configure — extraction IA desactivee'
                      : 'Aucun service cloud — mode 100 % local via Tika + Tesseract + regles'}
              </div>
              <div className="status-banner-sub">
                La page <strong>Etat systeme</strong> verifie en direct que les cles fonctionnent reellement (circuit breaker, latence, modele).
              </div>
            </div>
            <div className="status-banner-kpi">
              <strong>{aiLive && mistralLive ? 'Max' : aiLive || mistralLive ? 'Mixte' : 'Local'}</strong>
              mode actuel
            </div>
          </div>

          <div className="card">
            <div className="settings-toggle-row">
              <div>
                <div className="section-title-rail">
                  <Brain size={13} style={{ verticalAlign: '-2px' }} />&nbsp;
                  Anthropic Claude — extraction metier
                </div>
                <p className="settings-desc">
                  Classification du type de document puis extraction JSON structuree (montants, ICE, IF, RIB, lignes, retenues).
                  La cle saisie ici prend priorite sur la variable d'environnement <code style={codeStyle}>CLAUDE_API_KEY</code>.
                </p>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                {aiSettings?.apiKeyConfigured ? (
                  <span className="settings-ai-status configured">
                    <CheckCircle size={12} /> Cle presente
                  </span>
                ) : (
                  <span className="settings-ai-status not-configured">
                    <XCircle size={12} /> Cle manquante
                  </span>
                )}
                <label className="toggle">
                  <input type="checkbox" checked={aiEnabled} onChange={e => setAiEnabled(e.target.checked)} aria-label="Activer l'extraction IA" />
                  <span className="toggle-track" />
                  <span className="toggle-thumb" />
                </label>
              </div>
            </div>
            <div className={`settings-disableable ${aiEnabled ? '' : 'disabled'}`}>
              <div className="form-grid">
                <div>
                  <label className="form-label" htmlFor="ai-api-key"><Key size={11} /> Cle API Anthropic</label>
                  <div className="form-input-wrap">
                    <input
                      id="ai-api-key"
                      type={showApiKey ? 'text' : 'password'}
                      className="form-input"
                      value={aiApiKey}
                      onChange={e => setAiApiKey(e.target.value)}
                      placeholder="sk-ant-..."
                      style={{ paddingRight: 36, fontFamily: 'var(--font-mono)', fontSize: 12 }}
                    />
                    <button
                      type="button"
                      className="form-input-icon"
                      onClick={() => setShowApiKey(!showApiKey)}
                      aria-label={showApiKey ? 'Masquer la cle API' : 'Afficher la cle API'}
                    >
                      {showApiKey ? <EyeOff size={14} /> : <Eye size={14} />}
                    </button>
                  </div>
                </div>
                <div>
                  <label className="form-label" htmlFor="ai-model"><Cpu size={11} /> Modele</label>
                  <select id="ai-model" className="form-select full-width" value={aiModel} onChange={e => setAiModel(e.target.value)}>
                    {AI_MODELS.map(m => <option key={m.value} value={m.value}>{m.label} — {m.desc}</option>)}
                  </select>
                </div>
                <div className="form-grid-full">
                  <label className="form-label" htmlFor="ai-base-url"><Globe size={11} /> URL de base</label>
                  <input
                    id="ai-base-url"
                    type="text" className="form-input" value={aiBaseUrl}
                    onChange={e => setAiBaseUrl(e.target.value)}
                    placeholder="https://api.anthropic.com"
                    style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}
                  />
                </div>
              </div>
            </div>
            <button className="btn btn-primary" disabled={aiSaving} onClick={handleSaveAi}>
              {aiSaving ? <><Loader2 size={14} className="spin" /> Sauvegarde...</> : <><Shield size={14} /> Sauvegarder Claude</>}
            </button>
          </div>

          <div className="card">
            <div className="settings-toggle-row">
              <div>
                <div className="section-title-rail">
                  <Sparkles size={13} style={{ verticalAlign: '-2px' }} />&nbsp;
                  Mistral OCR — scans premium
                </div>
                <p className="settings-desc">
                  OCR cloud pour les PDF dont Tika extrait moins de 200 mots (scans, photos, cachets).
                  Rend du Markdown propre avec tableaux preserves, FR + AR natif, ~0.001 $/page.
                  Sans cle, Tesseract local prend le relai (0 $, precision moindre).
                </p>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                {ocrSettings?.mistralApiKeyConfigured ? (
                  <span className="settings-ai-status configured">
                    <CheckCircle size={12} /> Cle presente
                  </span>
                ) : (
                  <span className="settings-ai-status not-configured">
                    <XCircle size={12} /> Cle manquante
                  </span>
                )}
                <label className="toggle">
                  <input type="checkbox" checked={mistralEnabled} onChange={e => setMistralEnabled(e.target.checked)} aria-label="Activer Mistral OCR" />
                  <span className="toggle-track" />
                  <span className="toggle-thumb" />
                </label>
              </div>
            </div>
            <div className={`settings-disableable ${mistralEnabled ? '' : 'disabled'}`}>
              <div className="form-grid">
                <div>
                  <label className="form-label" htmlFor="mistral-api-key"><Key size={11} /> Cle API Mistral</label>
                  <div className="form-input-wrap">
                    <input
                      id="mistral-api-key"
                      type={showMistralKey ? 'text' : 'password'}
                      className="form-input"
                      value={mistralApiKey}
                      onChange={e => setMistralApiKey(e.target.value)}
                      placeholder="..."
                      style={{ paddingRight: 36, fontFamily: 'var(--font-mono)', fontSize: 12 }}
                    />
                    <button
                      type="button"
                      className="form-input-icon"
                      onClick={() => setShowMistralKey(!showMistralKey)}
                      aria-label={showMistralKey ? 'Masquer la cle API' : 'Afficher la cle API'}
                    >
                      {showMistralKey ? <EyeOff size={14} /> : <Eye size={14} />}
                    </button>
                  </div>
                </div>
                <div>
                  <label className="form-label" htmlFor="mistral-model"><Cpu size={11} /> Modele</label>
                  <select id="mistral-model" className="form-select full-width" value={mistralModel} onChange={e => setMistralModel(e.target.value)}>
                    {MISTRAL_OCR_MODELS.map(m => <option key={m.value} value={m.value}>{m.label} — {m.desc}</option>)}
                  </select>
                </div>
                <div className="form-grid-full">
                  <label className="form-label" htmlFor="mistral-base-url"><Globe size={11} /> URL de base</label>
                  <input
                    id="mistral-base-url"
                    type="text" className="form-input" value={mistralBaseUrl}
                    onChange={e => setMistralBaseUrl(e.target.value)}
                    placeholder="https://api.mistral.ai"
                    style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}
                  />
                </div>
              </div>
            </div>
            <button className="btn btn-primary" disabled={ocrSaving} onClick={handleSaveOcr}>
              {ocrSaving ? <><Loader2 size={14} className="spin" /> Sauvegarde...</> : <><Shield size={14} /> Sauvegarder Mistral</>}
            </button>
          </div>

          <div className="alert alert-info">
            <Info size={14} style={{ flexShrink: 0 }} aria-hidden="true" />
            <span>
              Les cles sont stockees chiffrees en base (<code style={codeStyle}>app_settings</code>).
              Pour revenir aux variables d'environnement, desactivez le toggle et sauvegardez avec un champ vide —
              la chaine de resolution tombe sur <code style={codeStyle}>CLAUDE_API_KEY</code> / <code style={codeStyle}>MISTRAL_API_KEY</code>.
            </span>
          </div>
        </div>
      )}

      {/* =========================================================== */}
      {/* HEALTH TAB                                                   */}
      {/* =========================================================== */}
      {activeTab === 'health' && <HealthPanel />}

      {/* =========================================================== */}
      {/* RULES TAB                                                    */}
      {/* =========================================================== */}
      {activeTab === 'rules' && (
        <div role="tabpanel" id="tab-panel-rules">
          <SettingsHero
            eyebrow="Regles metier"
            title={<>22 controles croises entre <span style={{ color: 'var(--accent-deep)' }}>facture, BC et OP</span>.</>}
            icon={<ShieldCheck size={24} aria-hidden="true" />}
            lead="Le moteur de validation rapproche les donnees extraites par Claude. Chaque regle est parametrable globalement et par dossier. Les regles ayant trait aux bons de commande (BC) ou aux contrats (Contractuel) sont signalees par une coche."
            status="active"
            statusLabel="Moteur local"
            kpi={`${ALL_RULES.length}`}
            kpiLabel="Regles definies"
          />
          <ValidationRulesSection />
        </div>
      )}

      {/* =========================================================== */}
      {/* ABOUT TAB                                                    */}
      {/* =========================================================== */}
      {activeTab === 'about' && (
        <div role="tabpanel" id="tab-panel-about">
          <SettingsHero
            eyebrow="ReconDoc · v1.3"
            title={<>MADAEF · <span style={{ color: 'var(--accent-deep)' }}>Groupe CDG</span></>}
            icon={<Info size={24} aria-hidden="true" />}
            lead="Plateforme de reconciliation documentaire des dossiers de paiement fournisseurs. Architecture serveur unique Kotlin + Spring Boot, front React, stockage PostgreSQL + S3. Zero microservice OCR a babysitter."
            status="active"
            statusLabel="En production"
            kpi="Kotlin · React"
            kpiLabel="Runtime"
          />

          <div className="card">
            <div className="section-title-rail">Stack deployee</div>
            <div className="about-meta">
              <div className="about-meta-item">
                <div className="lbl">Backend</div>
                <div className="val">Spring Boot 3.4</div>
                <div className="sub">Kotlin 2.1 · Java 21 · Flyway · JPA · Resilience4j</div>
              </div>
              <div className="about-meta-item">
                <div className="lbl">Pipeline OCR</div>
                <div className="val">Tika + Mistral + Tesseract</div>
                <div className="sub">Cascade adaptative, ~80 % local gratuit</div>
              </div>
              <div className="about-meta-item">
                <div className="lbl">Extraction IA</div>
                <div className="val">Claude {aiSettings?.model?.replace('claude-', '') || 'Sonnet 4.6'}</div>
                <div className="sub">Classification + extraction structuree</div>
              </div>
              <div className="about-meta-item">
                <div className="lbl">Stockage</div>
                <div className="val">PostgreSQL 16</div>
                <div className="sub">Donnees metier · documents sur S3-compatible</div>
              </div>
              <div className="about-meta-item">
                <div className="lbl">Front</div>
                <div className="val">React 18 · Vite</div>
                <div className="sub">TypeScript strict · Tailwind tokens + design editorial</div>
              </div>
              <div className="about-meta-item">
                <div className="lbl">Hebergement</div>
                <div className="val">Railway</div>
                <div className="sub">Backend · Front · Postgres manages</div>
              </div>
            </div>
          </div>

          <div className="card">
            <div className="section-title-rail">Raccourcis clavier</div>
            <table className="data-table">
              <thead><tr><th style={{ width: 120 }}>Raccourci</th><th>Action</th></tr></thead>
              <tbody>
                {SHORTCUTS.map(s => (
                  <tr key={s.keys}>
                    <td><kbd className="kbd-key">{s.keys}</kbd></td>
                    <td>{s.desc}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="settings-desc" style={{ marginTop: 8 }}>
              Schema complet du pipeline : onglet <strong>Pipeline</strong>.
            </p>
          </div>

          <div className="card">
            <div className="section-title-rail">Endpoints d'ops</div>
            <div className="howto-steps">
              <div className="howto-step">
                <div className="howto-step-num"><Server size={11} /></div>
                <div>
                  <div className="howto-step-title">
                    Healthcheck
                    <span className="pill-meta">GET /actuator/health</span>
                  </div>
                  <div className="howto-step-desc">
                    Etat de la DB, du circuit Claude et du moteur OCR (Mistral UP / Tesseract fallback).
                  </div>
                </div>
              </div>
              <div className="howto-step">
                <div className="howto-step-num"><Server size={11} /></div>
                <div>
                  <div className="howto-step-title">
                    Suivi des couts IA
                    <span className="pill-meta">GET /api/admin/claude-usage/summary</span>
                  </div>
                  <div className="howto-step-desc">
                    Tokens consommes, repartition par modele, dossiers les plus couteux, taux d'erreur.
                  </div>
                </div>
              </div>
              <div className="howto-step">
                <div className="howto-step-num"><Server size={11} /></div>
                <div>
                  <div className="howto-step-title">
                    Metriques Prometheus
                    <span className="pill-meta">GET /actuator/prometheus</span>
                  </div>
                  <div className="howto-step-desc">
                    Latence OCR, profondeur HikariCP, etat Resilience4j, JVM memory.
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

const codeStyle: React.CSSProperties = {
  fontFamily: 'var(--font-mono)',
  fontSize: 11,
  background: 'var(--ink-05)',
  padding: '1px 6px',
  borderRadius: 3,
  color: 'var(--ink-70)',
}

// ----------------------------------------------------------------------------

type HeroStatus = 'active' | 'idle' | 'off'

function SettingsHero({
  eyebrow, title, icon, lead, status, statusLabel, kpi, kpiLabel,
}: {
  eyebrow: string
  title: React.ReactNode
  icon?: React.ReactNode
  lead: string
  status: HeroStatus
  statusLabel: string
  kpi: string
  kpiLabel: string
}) {
  const dotTone = status === 'active' ? 'tone-active' : status === 'idle' ? 'tone-fallback' : ''
  return (
    <div className="settings-hero">
      <div style={{ position: 'relative', zIndex: 1 }}>
        <div className="settings-hero-eyebrow">{eyebrow}</div>
        <h2 className="settings-hero-title">
          {icon}
          <span>{title}</span>
        </h2>
        <p className="settings-hero-lead">{lead}</p>
        <div className={`status-banner ${dotTone}`} style={{
          marginTop: 16, marginBottom: 0, gridTemplateColumns: 'auto 1fr',
          background: 'transparent', border: 'none', padding: '0',
        }}>
          <span className="status-banner-dot" aria-hidden="true" />
          <div className="status-banner-title" style={{ fontSize: 12, fontWeight: 600 }}>
            {statusLabel}
          </div>
        </div>
      </div>
      <div className="settings-hero-aside">
        <span>{kpiLabel.toUpperCase()}</span>
        <strong>{kpi}</strong>
      </div>
    </div>
  )
}

// ----------------------------------------------------------------------------

type NodeState = 'active' | 'fallback' | 'off'

function PipelineNode({
  tag, name, role, meta, badge, state, icon,
}: {
  tag: string
  name: string
  role: string
  meta: string
  badge: string
  state: NodeState
  icon?: React.ReactNode
}) {
  const cls = state === 'active' ? 'is-active' : state === 'fallback' ? 'is-fallback' : 'is-off'
  return (
    <div className={`pipeline-node ${cls}`}>
      <div className="pipeline-node-icon" aria-hidden="true">
        {icon ?? tag}
      </div>
      <div className="pipeline-node-body">
        <h4>
          <span>{name}</span>
          <span style={{ fontFamily: 'var(--font-mono)', fontSize: 10, color: 'var(--ink-40)', fontWeight: 600 }}>
            · {tag}
          </span>
        </h4>
        <p>{role}</p>
      </div>
      <div className="pipeline-node-meta">
        <span className="pipeline-node-badge">{badge}</span>
        <span>{meta}</span>
      </div>
    </div>
  )
}

// ----------------------------------------------------------------------------

function HealthPanel() {
  const { toast } = useToast()
  const [health, setHealth] = useState<SystemHealthResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [lastFetch, setLastFetch] = useState<Date | null>(null)

  const load = useCallback(async (silent = false) => {
    if (!silent) setLoading(true)
    try {
      const data = await getSystemHealth()
      setHealth(data)
      setLastFetch(new Date())
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Impossible de recuperer l\'etat systeme')
    } finally {
      if (!silent) setLoading(false)
    }
  }, [toast])

  useEffect(() => { load() }, [load])

  useEffect(() => {
    const id = setInterval(() => load(true), 15000)
    return () => clearInterval(id)
  }, [load])

  const worstTone = deriveWorstTone(health?.components)
  const statusLabel =
    worstTone === 'error' ? 'Incident en cours'
    : worstTone === 'warn' ? 'Mode degrade'
    : 'Tout est operationnel'

  return (
    <div role="tabpanel" id="tab-panel-health">
      <SettingsHero
        eyebrow="Observabilite"
        title={<>Surveiller en un coup d'oeil <span style={{ color: 'var(--accent-deep)' }}>chaque brique</span> de la plateforme.</>}
        icon={<Activity size={24} aria-hidden="true" />}
        lead="Chaque composant est teste en direct : PostgreSQL (SELECT 1), stockage, pipeline OCR, integration Claude, memoire JVM. La vue s'auto-actualise toutes les 15 s. Utilisez cet ecran avant une validation en masse ou pour diagnostiquer un incident."
        status={worstTone === 'ok' ? 'active' : worstTone === 'warn' ? 'idle' : 'off'}
        statusLabel={statusLabel}
        kpi={health?.uptime || '—'}
        kpiLabel="Uptime"
      />

      <div className="card">
        <div className="section-title-rail" style={{ marginTop: 0 }}>
          Composants surveilles
          <span style={{
            marginLeft: 'auto',
            fontFamily: 'var(--font-mono)', fontSize: 10,
            fontWeight: 500, letterSpacing: 0.5, textTransform: 'none',
            color: 'var(--ink-40)', display: 'flex', alignItems: 'center', gap: 8,
          }}>
            {lastFetch && (
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                <Clock size={10} /> {formatRelative(lastFetch)}
              </span>
            )}
            <button
              className="btn btn-secondary"
              style={{ fontSize: 11, padding: '4px 10px' }}
              onClick={() => load()}
              disabled={loading}
              aria-label="Rafraichir l'etat systeme"
            >
              {loading
                ? <><Loader2 size={12} className="spin" /> Refresh</>
                : <><RefreshCw size={12} /> Refresh</>}
            </button>
          </span>
        </div>

        {!health && !loading && (
          <div className="alert alert-warning">
            <AlertTriangle size={14} />
            <span>Pas de donnees. Vérifiez que le backend est joignable.</span>
          </div>
        )}

        {health && (
          <div className="health-grid">
            {health.components.map(comp => (
              <HealthCard key={comp.id} comp={comp} />
            ))}
          </div>
        )}
      </div>

      {health && (
        <div className="card">
          <div className="section-title-rail" style={{ marginTop: 0 }}>Informations runtime</div>
          <div className="about-meta">
            <div className="about-meta-item">
              <div className="lbl">Application</div>
              <div className="val">{health.application.name}</div>
              <div className="sub">Demarree le {formatDateTime(health.application.startedAt)}</div>
            </div>
            <div className="about-meta-item">
              <div className="lbl">JVM</div>
              <div className="val">Java {health.application.javaVersion}</div>
              <div className="sub">Timezone {health.application.timezone}</div>
            </div>
            <div className="about-meta-item">
              <div className="lbl">Derniere verif</div>
              <div className="val">{formatDateTime(health.checkedAt)}</div>
              <div className="sub">Auto-refresh toutes les 15 s</div>
            </div>
          </div>
        </div>
      )}

      <div className="card">
        <div className="section-title-rail" style={{ marginTop: 0 }}>Comment lire cette page</div>
        <div className="howto-steps">
          <div className="howto-step">
            <div className="howto-step-num" style={{ background: 'rgba(16,185,129,0.1)', color: 'var(--accent-deep)' }}>●</div>
            <div>
              <div className="howto-step-title">Vert — Operationnel <span className="pill-meta accent">tone:ok</span></div>
              <div className="howto-step-desc">Le composant repond normalement, dans les temps attendus.</div>
            </div>
          </div>
          <div className="howto-step">
            <div className="howto-step-num" style={{ background: 'rgba(245,158,11,0.1)', color: '#b45309' }}>●</div>
            <div>
              <div className="howto-step-title">Ambre — Mode degrade <span className="pill-meta warn">tone:warn</span></div>
              <div className="howto-step-desc">
                Le composant fonctionne mais en mode de repli : par exemple OCR sans Mistral (Tesseract seul),
                ou circuit breaker Claude OPEN apres des erreurs.
              </div>
            </div>
          </div>
          <div className="howto-step">
            <div className="howto-step-num" style={{ background: 'rgba(239,68,68,0.1)', color: 'var(--danger)' }}>●</div>
            <div>
              <div className="howto-step-title">Rouge — Indisponible <span className="pill-meta" style={{ background: 'rgba(239,68,68,0.08)', color: 'var(--danger)' }}>tone:error</span></div>
              <div className="howto-step-desc">
                Incident reel — la DB ne repond plus, ou la memoire JVM est saturee.
                Rapprocher l'incident d'un deploiement recent ou d'un pic de charge.
              </div>
            </div>
          </div>
          <div className="howto-step">
            <div className="howto-step-num" style={{ background: 'var(--ink-05)', color: 'var(--ink-50)' }}>○</div>
            <div>
              <div className="howto-step-title">Gris — Desactive <span className="pill-meta">tone:muted</span></div>
              <div className="howto-step-desc">
                Composant optionnel non configure (ex : Claude sans cle API). Sans impact sur le healthcheck
                global — la plateforme sert toujours les requetes.
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

function deriveWorstTone(components?: HealthComponent[]): HealthTone {
  if (!components?.length) return 'muted'
  if (components.some(c => c.tone === 'error')) return 'error'
  if (components.some(c => c.tone === 'warn')) return 'warn'
  if (components.every(c => c.tone === 'muted')) return 'muted'
  return 'ok'
}

function HealthCard({ comp }: { comp: HealthComponent }) {
  const details = comp.details as Record<string, unknown> | undefined
  return (
    <div className={`health-card health-tone-${comp.tone}`}>
      <div className="health-card-head">
        <div className="health-card-icon" aria-hidden="true">{renderComponentIcon(comp.id)}</div>
        <div className="health-card-title">
          <span className="health-card-label">{comp.label}</span>
          <span className="health-card-cat">{comp.category}</span>
        </div>
        <StatusDot tone={comp.tone} />
      </div>
      <div className="health-card-status">{prettyStatus(comp.status)}</div>
      {typeof comp.latencyMs === 'number' && comp.latencyMs >= 0 && (
        <div className="health-card-kv">
          <span className="health-card-key">Latence</span>
          <span className="health-card-val mono">{comp.latencyMs} ms</span>
        </div>
      )}
      {details && Object.entries(details).filter(([, v]) => v !== null && v !== undefined && v !== '').map(([k, v]) => (
        <div className="health-card-kv" key={k}>
          <span className="health-card-key">{humanizeKey(k)}</span>
          <span className="health-card-val mono">{formatValue(v)}</span>
        </div>
      ))}
    </div>
  )
}

function StatusDot({ tone }: { tone: HealthTone }) {
  return <span className={`health-dot health-dot-${tone}`} aria-hidden="true" />
}

function renderComponentIcon(id: string) {
  switch (id) {
    case 'db': return <Database size={14} />
    case 'ocr': return <ScanLine size={14} />
    case 'ai': return <Brain size={14} />
    case 'storage': return <HardDrive size={14} />
    case 'jvm': return <Cpu size={14} />
    default: return <Server size={14} />
  }
}

function prettyStatus(s: string) {
  switch (s) {
    case 'up': return 'En ligne'
    case 'degraded': return 'Degrade'
    case 'down': return 'Hors-ligne'
    case 'off': return 'Desactive'
    default: return s
  }
}

function humanizeKey(k: string): string {
  const map: Record<string, string> = {
    version: 'Version',
    error: 'Erreur',
    type: 'Type',
    presignSupported: 'URL signees',
    usableSpaceMb: 'Espace libre',
    totalSpaceMb: 'Espace total',
    engine: 'Moteur',
    fallback: 'Fallback',
    mistralConfigured: 'Cle Mistral',
    mistralEnabled: 'Mistral actif',
    model: 'Modele',
    mode: 'Mode',
    apiKeyConfigured: 'Cle API',
    enabled: 'Active',
    circuit: 'Circuit breaker',
    failureRate: 'Taux d\'erreur',
    usedMb: 'Utilise',
    totalMb: 'Total',
    maxMb: 'Max',
    usagePct: 'Utilisation',
    availableProcessors: 'CPUs',
  }
  return map[k] || k
}

function formatValue(v: unknown): string {
  if (v === true) return 'oui'
  if (v === false) return 'non'
  if (typeof v === 'number') {
    if (Number.isInteger(v)) return v.toLocaleString('fr-FR')
    return v.toFixed(2)
  }
  if (typeof v === 'string') {
    if (v.length > 60) return v.slice(0, 57) + '...'
    return v
  }
  return String(v)
}

function formatDateTime(iso: string): string {
  try {
    const d = new Date(iso)
    return d.toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
  } catch { return iso }
}

function formatRelative(d: Date): string {
  const diff = Math.round((Date.now() - d.getTime()) / 1000)
  if (diff < 5) return 'a l\'instant'
  if (diff < 60) return `il y a ${diff}s`
  const m = Math.floor(diff / 60)
  if (m < 60) return `il y a ${m} min`
  return d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
}

// ----------------------------------------------------------------------------

function ValidationRulesSection() {
  const [disabled, setDisabled] = useState<Set<string>>(getDisabledRules)

  const toggle = (code: string) => {
    const next = new Set(disabled)
    if (next.has(code)) next.delete(code); else next.add(code)
    setDisabled(next)
    setDisabledRules(next)
  }

  const systemRules = ALL_RULES.filter(r => r.category === 'system')
  const checklistRules = ALL_RULES.filter(r => r.category === 'checklist')

  return (
    <div className="card">
      <div className="section-title-rail">
        Regles systeme · {systemRules.filter(r => !disabled.has(r.code)).length}/{systemRules.length} actives
      </div>
      <table className="data-table" style={{ marginBottom: 20 }}>
        <thead>
          <tr>
            <th style={{ width: 50 }}>Actif</th>
            <th style={{ width: 70 }}>Code</th>
            <th>Regle</th>
            <th>Description</th>
            <th style={{ width: 80 }}>BC</th>
            <th style={{ width: 80 }}>Contrat</th>
          </tr>
        </thead>
        <tbody>
          {systemRules.map(r => (
            <tr key={r.code} style={{ opacity: disabled.has(r.code) ? 0.4 : 1 }}>
              <td>
                <label className="toggle">
                  <input type="checkbox" checked={!disabled.has(r.code)} onChange={() => toggle(r.code)} aria-label={`Activer la regle ${r.code}`} />
                  <span className="toggle-track" />
                  <span className="toggle-thumb" />
                </label>
              </td>
              <td className="rule-code">{r.code}</td>
              <td className="rule-label">{r.label}</td>
              <td className="rule-desc">{r.desc}</td>
              <td>{r.appliesToBC ? <CheckCircle size={12} style={{ color: 'var(--success)' }} aria-label="Applicable" /> : <span style={{ color: 'var(--ink-20)' }} aria-label="Non applicable">—</span>}</td>
              <td>{r.appliesToContractuel ? <CheckCircle size={12} style={{ color: 'var(--success)' }} aria-label="Applicable" /> : <span style={{ color: 'var(--ink-20)' }} aria-label="Non applicable">—</span>}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <div className="section-title-rail">
        Points de controle TC · {checklistRules.filter(r => !disabled.has(r.code)).length}/{checklistRules.length} actifs
      </div>
      <table className="data-table">
        <thead>
          <tr>
            <th style={{ width: 50 }}>Actif</th>
            <th style={{ width: 70 }}>Code</th>
            <th>Point de controle</th>
            <th>Description</th>
          </tr>
        </thead>
        <tbody>
          {checklistRules.map(r => (
            <tr key={r.code} style={{ opacity: disabled.has(r.code) ? 0.4 : 1 }}>
              <td>
                <label className="toggle">
                  <input type="checkbox" checked={!disabled.has(r.code)} onChange={() => toggle(r.code)} aria-label={`Activer le point ${r.code}`} />
                  <span className="toggle-track" />
                  <span className="toggle-thumb" />
                </label>
              </td>
              <td className="rule-code">{r.code}</td>
              <td className="rule-label">{r.label}</td>
              <td className="rule-desc">{r.desc}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
