'use client';

import { Children, useRef, type ReactNode } from 'react';
import { useTranslate } from './locale';
import { ChevronDown, Inbox, Loader2, X } from 'lucide-react';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';

export function Action({ children, onClick, primary = false, disabled = false, busy = false, type = 'button' }: {
  children: ReactNode; onClick?: () => void; primary?: boolean; disabled?: boolean; busy?: boolean; type?: 'button' | 'submit';
}) {
  const t = useTranslate();
  return <Button disableRipple type={type} disabled={disabled || busy} onClick={onClick} className={'action ' + (primary ? 'primary' : '')}>
    {busy && <Loader2 size={15} className="spin" />}{Children.map(children, child => typeof child === 'string' ? t(child.trim()) : child)}
  </Button>;
}

export function Select({ label, value, onChange, options, className = '' }: {
  label: string; value: string; onChange: (value: string) => void; options: [string, string][]; className?: string;
}) {
  const t = useTranslate();
  return <label className={'field ' + className}>{label && <span>{t(label)}</span>}<div className="select">
    <select aria-label={label || 'Filter'} value={value} onChange={e => onChange(e.target.value)}>
      {options.map(([id, text]) => <option key={id} value={id}>{t(text)}</option>)}
    </select><ChevronDown size={14} />
  </div></label>;
}

export function Field({ label, value, onChange, type = 'text', placeholder, required = false, min, max }: {
  label: string; value: string | number; onChange: (value: string) => void; type?: string; placeholder?: string;
  required?: boolean; min?: number; max?: number;
}) {
  const t = useTranslate();
  return <label className="field"><span>{t(label)}</span><input value={value} onChange={e => onChange(e.target.value)}
    type={type} placeholder={placeholder} required={required} min={min} max={max} autoComplete={type === 'password' ? 'new-password' : 'off'} /></label>;
}

export function Text({ label, value, onChange, rows = 4, mono = false }: {
  label: string; value: string; onChange: (value: string) => void; rows?: number; mono?: boolean;
}) {
  const t = useTranslate();
  return <label className="field"><span>{t(label)}</span><textarea className={mono ? 'mono' : ''} rows={rows}
    value={value} onChange={e => onChange(e.target.value)} spellCheck={!mono} /></label>;
}

export function Card({ title, note, actions, children, className = '' }: {
  title?: string; note?: string; actions?: ReactNode; children: ReactNode; className?: string;
}) {
  const t = useTranslate();
  return <section className={'card ' + className}>{title && <div className="card-head"><div><h2>{t(title)}</h2>
    {note && <p>{t(note)}</p>}</div>{actions}</div>}{children}</section>;
}

export function Empty({ title, text, action }: { title: string; text: string; action?: ReactNode }) {
  const t = useTranslate();
  return <div className="empty"><div className="empty-icon"><Inbox size={21}/></div><h3>{t(title)}</h3><p>{t(text)}</p>{action}</div>;
}

export function Modal({ title, children, onClose }: { title: string; children: ReactNode; onClose: () => void }) {
  const t = useTranslate();
  return <Dialog open onClose={onClose} fullWidth maxWidth="sm" slotProps={{ paper: { className: 'dialog-paper' } }}>
    <div className="dialog-head"><h2>{t(title)}</h2><button className="icon" aria-label="Close dialog" onClick={onClose}><X size={18}/></button></div>
    {children}
  </Dialog>;
}

export function Toggle({ label, checked, onChange, disabled = false }: {
  label: string; checked: boolean; onChange: (value: boolean) => void; disabled?: boolean;
}) {
  const t = useTranslate();
  return <label className="toggle-line"><input type="checkbox" checked={checked} disabled={disabled}
    onChange={e => onChange(e.target.checked)} /><span>{t(label)}</span></label>;
}

export function FileButton({ label, accept, onFile }: { label: string; accept: string; onFile: (text: string) => void }) {
  const ref = useRef<HTMLInputElement>(null);
  return <><Action onClick={() => ref.current?.click()}>{label}</Action><input hidden type="file" accept={accept} ref={ref}
    onChange={e => { const file = e.target.files?.[0]; if (file) void file.text().then(onFile); e.target.value = ''; }} /></>;
}
